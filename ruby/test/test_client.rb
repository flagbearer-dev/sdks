# frozen_string_literal: true

require "test_helper"

class TestClient < Minitest::Test
  include ClientHelpers

  USER = { key: "user-1" }.freeze

  def test_defaults_until_the_first_ruleset_then_local_rules
    @sdk.ruleset_statuses = [503] * 1000
    client = make_client
    assert client.enabled?("checkout", USER, default: true)
    refute client.enabled?("checkout", USER)

    @sdk.synchronize { @sdk.ruleset_statuses = [] }
    client.sync
    assert client.enabled?("checkout", USER)
    assert client.enabled?("missing", USER, default: true)
    assert client.enabled?("checkout", { "key" => "user-1" }), "string keys work too"
  end

  def test_a_stream_announcement_refetches_with_the_current_version
    client = make_client
    client.sync
    assert @sdk.wait_for_stream

    @sdk.synchronize do
      @sdk.flags = [rule("checkout", enabled: false)]
      @sdk.version = 2
    end
    @sdk.announce(2)
    wait_for { !client.enabled?("checkout", USER, default: true) }
    assert_includes @sdk.ruleset_requests, '"1"'
  end

  def test_an_unchanged_ruleset_keeps_the_rules
    client = make_client
    client.sync
    client.sync
    assert_equal '"1"', @sdk.ruleset_requests.last
    assert client.enabled?("checkout", USER)
  end

  def test_an_invalid_ruleset_raises_and_keeps_the_last_rules
    client = make_client
    client.sync
    @sdk.synchronize do
      @sdk.version = 2
      @sdk.ruleset_body = '{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}'
    end
    error = assert_raises(Flagbearer::Error) { client.sync }
    assert_predicate error, :permanent?
    assert client.enabled?("checkout", USER)

    # An explicit sync tries again after a permanent failure.
    @sdk.synchronize do
      @sdk.ruleset_body = nil
      @sdk.flags = [rule("checkout", enabled: false)]
    end
    client.sync
    refute client.enabled?("checkout", USER, default: true)
  end

  def test_sync_after_a_permanent_failure_restarts_polling
    @sdk.stream_status = 401
    client = make_client(timings: FAST.dup.tap { |t| t.poll_interval = 0.05 })
    client.sync
    @sdk.synchronize do
      @sdk.ruleset_body = "not json"
      @sdk.version = 2
    end
    assert_raises(Flagbearer::Error) { client.sync }

    @sdk.synchronize { @sdk.ruleset_body = nil }
    client.sync
    @sdk.synchronize do
      @sdk.flags = [rule("checkout", enabled: false)]
      @sdk.version = 3
    end
    wait_for { !client.enabled?("checkout", USER, default: true) }
  end

  def test_a_ruleset_for_another_environment_is_rejected
    client = make_client
    client.sync
    @sdk.synchronize do
      @sdk.environment_id = "env-2"
      @sdk.flags = [rule("checkout", enabled: false)]
      @sdk.version = 2
    end
    assert_raises(Flagbearer::Error, /environment changed/) { client.sync }
    assert client.enabled?("checkout", USER)
  end

  def test_server_errors_retry_in_the_background
    # Without a stream, only the retry schedule can recover.
    @sdk.stream_status = 401
    @sdk.ruleset_statuses = [503, 500, 429]
    client = make_client
    error = assert_raises(Flagbearer::Error) { client.sync }
    assert_predicate error, :retryable?
    wait_for { client.enabled?("checkout", USER) }
  end

  def test_sync_honors_its_timeout
    @sdk.ruleset_statuses = [503] * 5
    client = make_client(timings: FAST.dup.tap { |t| t.retry_base = t.max_retry = 3600.0 })
    assert_raises(Flagbearer::Error) { client.sync }
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    assert_raises(Flagbearer::Error, /timed out/) { client.sync(timeout: 0.05) }
    assert_operator Process.clock_gettime(Process::CLOCK_MONOTONIC) - started, :<, 1
  end

  def test_the_stream_outlives_its_connect_timeout_and_close_ends_it
    client = make_client(timings: FAST.dup.tap { |t| t.stream_connect_timeout = 0.05 })
    client.sync
    assert @sdk.wait_for_stream
    sleep 0.3
    assert_equal 1, @sdk.stream_connections

    # The idle timeout is 5 seconds, so only an abort ends the read this soon.
    client.close
    stream = client.instance_variable_get(:@stream)
    assert stream.thread.join(1), "stream still running after close"
  end

  def test_a_rejected_stream_falls_back_to_polling
    @sdk.stream_status = 401
    client = make_client(timings: FAST.dup.tap { |t| t.poll_interval = 0.05 })
    client.sync
    @sdk.synchronize do
      @sdk.flags = [rule("checkout", enabled: false)]
      @sdk.version = 2
    end
    wait_for { !client.enabled?("checkout", USER, default: true) }
  end

  def test_exposures_count_repeated_checks_and_batch_by_user
    client = make_client(app_version: "build-7")
    client.sync
    3.times { client.enabled?("checkout", { key: "user-1", email: " Ann@Example.com " }) }
    client.enabled?("checkout", { key: "user-2" })
    client.flush

    by_user = @sdk.exposures.to_h { |upload| [upload["user"]["key"], upload] }
    assert_equal({ "key" => "user-1", "email" => "ann@example.com" }, by_user["user-1"]["user"])
    event = by_user["user-1"]["events"].first
    assert_equal [3, true, "rollout", 1], event.values_at("checkCount", "result", "reason", "configurationVersion")
    assert_match(/\A\d{4}-\d\d-\d\dT\d\d:\d\d:[03]0\.000Z\z/, event["bucket"])
    assert_operator event["firstCheckedAt"], :>=, event["bucket"]
    assert_equal 1, by_user["user-2"]["events"].first["checkCount"]
    assert_equal ["ruby"], @sdk.exposures.map { |upload| upload["sdk"] }.uniq
    assert_equal [Flagbearer::VERSION], @sdk.exposures.map { |upload| upload["sdkVersion"] }.uniq
    assert_equal ["build-7"], @sdk.exposures.map { |upload| upload["appVersion"] }.uniq
    assert_equal 1, @sdk.exposures.map { |upload| upload["reporterId"] }.uniq.size
  end

  def test_a_failed_upload_retries_the_same_batch
    client = make_client
    client.sync
    client.enabled?("checkout", USER)
    @sdk.synchronize { @sdk.exposure_statuses = [500] }
    assert_raises(Flagbearer::Error) { client.flush }
    client.flush
    assert_equal 2, @sdk.exposures.size
    assert_equal @sdk.exposures[0]["batchId"], @sdk.exposures[1]["batchId"]
  end

  def test_a_refused_upload_is_dropped_and_counted_as_lost
    client = make_client
    client.sync
    2.times { client.enabled?("checkout", USER) }
    @sdk.synchronize { @sdk.exposure_statuses = [400] }
    assert_raises(Flagbearer::Error) { client.flush }
    client.flush
    assert_empty @sdk.exposures.last["events"]
    assert_equal 2, @sdk.exposures.last["droppedChecks"]
  end

  def test_checks_that_cant_be_recorded_are_counted_as_lost
    client = make_client
    client.sync
    assert client.enabled?("x" * 257, USER, default: true)
    # 200 emoji are 400 UTF-16 units: past the server's 256 limit.
    assert client.enabled?("checkout", { key: "😀" * 200 }, default: true)
    assert client.enabled?("checkout", { key: 42 }, default: true)
    client.flush
    assert_equal 3, @sdk.exposures.last["droppedChecks"]
    assert_empty @sdk.exposures.last["events"]
  end

  def test_keys_in_other_encodings_upload_as_utf8
    client = make_client
    client.sync
    client.enabled?("checkout", { key: "ü-user".encode("ISO-8859-1") })
    client.flush
    assert_equal "ü-user", @sdk.exposures.last["user"]["key"]
  end

  def test_close_flushes_once_and_keeps_rules_usable
    client = make_client
    client.sync
    client.enabled?("checkout", USER)
    client.close
    client.close
    assert_equal 1, @sdk.exposures.size
    assert client.enabled?("checkout", USER)
    assert_raises(Flagbearer::ClosedError) { client.sync }
  end

  def test_init_rejects_bad_keys_and_urls
    assert_raises(ArgumentError) { Flagbearer.init("") }
    assert_raises(ArgumentError) { Flagbearer.init("fbs_ bad") }
    assert_raises(ArgumentError) { Flagbearer.init("fbs_test", url: "ftp://example.com") }
  end

  def test_redirects_are_not_followed
    client = make_client
    @sdk.synchronize { @sdk.ruleset_statuses = [302] }
    error = assert_raises(Flagbearer::Error) { client.sync }
    assert_predicate error, :permanent?
  end
end
