import pytest

from flagbearer._stream import MAX_EVENT_LENGTH, EventParser


def parse(*pieces):
    versions = []
    parser = EventParser(versions.append)
    for piece in pieces:
        parser.feed(piece)
    return versions


def test_reads_version_events_and_ignores_others():
    assert parse(
        "retry: 9000\n\n",
        ": comment\n",
        'event: version\ndata: {"version":4}\n\n',
        "event: ping\ndata: {}\n\n",
        'data: {"version":99}\n\n',
    ) == [4]


@pytest.mark.parametrize("ending", ["\n", "\r\n", "\r"])
def test_any_line_ending_split_anywhere(ending):
    text = f'event: version{ending}data: {{"version":7}}{ending}{ending}'
    # A trailing CR might be half a CRLF, so it's read once the next byte arrives.
    following = ": heartbeat\n"
    for cut in range(len(text) + 1):
        assert parse(text[:cut], text[cut:], following) == [7], cut


def test_crlf_split_between_pieces_is_one_line_break():
    assert parse('event: version\r\ndata: {"version":3}\r', "\n\r\n") == [3]


def test_multi_line_data_joins_with_newlines():
    assert parse('event: version\ndata: {"version":\ndata: 5}\n\n') == [5]


@pytest.mark.parametrize(
    "data", ["nope", '{"version":-1}', '{"version":true}', '{"version":1.5}', "[]"]
)
def test_malformed_versions_are_ignored(data):
    assert parse(f"event: version\ndata: {data}\n\n") == []


def test_oversized_events_fail_the_connection():
    with pytest.raises(ValueError):
        parse("data: " + "x" * MAX_EVENT_LENGTH + "\n")
    with pytest.raises(ValueError):
        parse("x" * (MAX_EVENT_LENGTH + 1))
