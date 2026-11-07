import dataclasses
import threading

import pytest
from conftest import FAST, rule, wait_for

import flagbearer
from flagbearer._http import RefreshError

USER = {"key": "user-1"}


def test_checks_return_the_default_before_any_ruleset(sdk, make_client):
    sdk.ruleset_statuses = [503] * 1000
    client = make_client()
    assert client.is_enabled("checkout", USER, default=True) is True
    assert client.is_enabled("checkout", USER) is False


def test_checks_use_the_rules_after_a_sync(sdk, make_client):
    client = make_client()
    client.sync()
    assert client.is_enabled("checkout", USER) is True
    assert client.is_enabled("missing", USER, default=True) is True


def test_a_stream_announcement_refetches_with_the_current_version(sdk, make_client):
    client = make_client()
    client.sync()
    sdk.stream_opened.wait(2)

    sdk.flags = [rule("checkout", enabled=False)]
    sdk.version = 2
    sdk.announce(2)

    wait_for(lambda: client.is_enabled("checkout", USER) is False)
    assert '"1"' in sdk.ruleset_requests


def test_an_unchanged_ruleset_keeps_the_rules(sdk, make_client):
    client = make_client()
    client.sync()
    client.sync()
    assert sdk.ruleset_requests[-1] == '"1"'
    assert client.is_enabled("checkout", USER) is True


def test_an_invalid_ruleset_raises_and_keeps_the_last_rules(sdk, make_client):
    client = make_client()
    client.sync()

    sdk.version = 2
    sdk.ruleset_body = b'{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}'
    with pytest.raises(RefreshError) as raised:
        client.sync()
    assert raised.value.retryable is False
    assert client.is_enabled("checkout", USER) is True

    # An explicit sync tries again after a permanent failure.
    sdk.ruleset_body = None
    sdk.flags = [rule("checkout", enabled=False)]
    client.sync()
    assert client.is_enabled("checkout", USER) is False


def test_sync_after_a_permanent_failure_restarts_polling(sdk, make_client):
    sdk.stream_status = 401
    client = make_client(timings=dataclasses.replace(FAST, poll_interval=0.05))
    client.sync()
    sdk.ruleset_body = b"not json"
    sdk.version = 2
    with pytest.raises(RefreshError):
        client.sync()

    sdk.ruleset_body = None
    client.sync()
    sdk.flags = [rule("checkout", enabled=False)]
    sdk.version = 3
    wait_for(lambda: client.is_enabled("checkout", USER) is False)


def test_a_ruleset_for_another_environment_is_rejected(sdk, make_client):
    client = make_client()
    client.sync()
    sdk.environment_id = "env-2"
    sdk.flags = [rule("checkout", enabled=False)]
    sdk.version = 2
    with pytest.raises(RefreshError, match="environment changed"):
        client.sync()
    assert client.is_enabled("checkout", USER) is True


def test_server_errors_retry_in_the_background(sdk, make_client):
    # Without a stream, only the retry schedule can recover.
    sdk.stream_status = 401
    sdk.ruleset_statuses = [503, 500, 429]
    client = make_client()
    with pytest.raises(RefreshError):
        client.sync()
    wait_for(lambda: client.is_enabled("checkout", USER) is True)
    assert len(sdk.ruleset_requests) >= 4


def test_the_stream_outlives_its_connect_timeout_and_close_ends_it(sdk, make_client):
    client = make_client(timings=dataclasses.replace(FAST, stream_connect_timeout=0.05))
    client.sync()
    sdk.stream_opened.wait(2)
    threading.Event().wait(0.3)
    assert sdk.stream_connections == 1

    # The idle timeout is 5 seconds, so only an abort ends the read this soon.
    client.close()
    client._stream._thread.join(1)
    assert not client._stream._thread.is_alive()


def test_a_silent_stream_is_dropped_and_reconnected(sdk, make_client):
    client = make_client(timings=dataclasses.replace(FAST, stream_idle_timeout=0.1))
    client.sync()
    # The fake sends nothing after its opening event, like a dead connection.
    wait_for(lambda: sdk.stream_connections >= 2)


def test_a_rejected_stream_falls_back_to_polling(sdk, make_client):
    sdk.stream_status = 401
    client = make_client(timings=dataclasses.replace(FAST, poll_interval=0.05))
    client.sync()

    sdk.flags = [rule("checkout", enabled=False)]
    sdk.version = 2
    wait_for(lambda: client.is_enabled("checkout", USER) is False)


def test_exposures_count_repeated_checks_and_batch_by_user(sdk, make_client):
    client = make_client(app_version="build-7")
    client.sync()
    for _ in range(3):
        client.is_enabled("checkout", {"key": "user-1", "email": " Ann@Example.com "})
    client.is_enabled("checkout", {"key": "user-2"})
    client.flush()

    by_user = {upload["user"].get("key"): upload for upload in sdk.exposures}
    assert by_user["user-1"]["user"] == {"key": "user-1", "email": "ann@example.com"}
    [event] = by_user["user-1"]["events"]
    assert event["checkCount"] == 3
    assert (event["result"], event["reason"], event["configurationVersion"]) == (True, "rollout", 1)
    assert event["bucket"].endswith("Z") and event["firstCheckedAt"] >= event["bucket"]
    assert by_user["user-2"]["events"][0]["checkCount"] == 1
    assert {upload["sdk"] for upload in sdk.exposures} == {"python"}
    assert {upload["appVersion"] for upload in sdk.exposures} == {"build-7"}
    assert len({upload["reporterId"] for upload in sdk.exposures}) == 1


def test_a_failed_upload_retries_the_same_batch(sdk, make_client):
    client = make_client()
    client.sync()
    client.is_enabled("checkout", USER)
    sdk.exposure_statuses = [500]
    with pytest.raises(RuntimeError):
        client.flush()
    client.flush()
    assert len(sdk.exposures) == 2
    assert sdk.exposures[0]["batchId"] == sdk.exposures[1]["batchId"]


def test_a_refused_upload_is_dropped_and_counted_as_lost(sdk, make_client):
    client = make_client()
    client.sync()
    client.is_enabled("checkout", USER)
    client.is_enabled("checkout", USER)
    sdk.exposure_statuses = [400]
    with pytest.raises(RuntimeError):
        client.flush()
    client.flush()
    assert sdk.exposures[-1]["events"] == []
    assert sdk.exposures[-1]["droppedChecks"] == 2


def test_checks_that_cant_be_recorded_are_counted_as_lost(sdk, make_client):
    client = make_client()
    client.sync()
    assert client.is_enabled("x" * 257, USER, default=True) is True
    assert client.is_enabled("checkout", {"email": 5}, default=True) is True
    # 200 emoji are 400 UTF-16 units: past the server's 256 limit.
    assert client.is_enabled("checkout", {"key": "😀" * 200}, default=True) is True
    client.flush()
    assert sdk.exposures[-1]["droppedChecks"] == 3
    assert sdk.exposures[-1]["events"] == []


def test_close_flushes_once_and_keeps_rules_usable(sdk, make_client):
    client = make_client()
    client.sync()
    client.is_enabled("checkout", USER)
    client.close()
    client.close()
    assert len(sdk.exposures) == 1
    assert client.is_enabled("checkout", USER) is True
    with pytest.raises(RuntimeError, match="closed"):
        client.sync()


def test_init_rejects_bad_keys_and_urls(sdk):
    with pytest.raises(ValueError):
        flagbearer.init("")
    with pytest.raises(ValueError):
        flagbearer.init("fbs_ bad")
    with pytest.raises(ValueError):
        flagbearer.init("fbs_test", url="ftp://example.com")
