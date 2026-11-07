# frozen_string_literal: true

require "test_helper"

class TestStream < Minitest::Test
  def parse(*pieces)
    versions = []
    parser = Flagbearer::EventParser.new { |version| versions << version }
    pieces.each { |piece| parser.feed(piece) }
    versions
  end

  def test_reads_version_events_and_ignores_others
    assert_equal [4], parse(
      "retry: 9000\n\n", ": comment\n", "event: version\ndata: {\"version\":4}\n\n",
      "event: ping\ndata: {}\n\n", "data: {\"version\":99}\n\n"
    )
  end

  def test_any_line_ending_split_anywhere
    ["\n", "\r\n", "\r"].each do |ending|
      text = "event: version#{ending}data: {\"version\":7}#{ending}#{ending}"
      (0..text.bytesize).each do |cut|
        # A trailing CR might be half a CRLF, so it's read once the next byte arrives.
        assert_equal [7], parse(text.byteslice(0, cut), text.byteslice(cut..), ": heartbeat\n"), "#{ending.inspect} #{cut}"
      end
    end
  end

  def test_multi_byte_characters_split_across_reads
    text = "event: version\ndata: {\"version\":5,\"note\":\"ü😀\"}\n\n".b
    (0..text.bytesize).each do |cut|
      assert_equal [5], parse(text.byteslice(0, cut), text.byteslice(cut..)), cut.to_s
    end
  end

  def test_multi_line_data_joins_with_newlines
    assert_equal [5], parse("event: version\ndata: {\"version\":\ndata: 5}\n\n")
  end

  def test_malformed_versions_are_ignored
    ["nope", '{"version":-1}', '{"version":true}', '{"version":1.5}', "[]"].each do |data|
      assert_empty parse("event: version\ndata: #{data}\n\n"), data
    end
  end

  def test_oversized_events_fail_the_connection
    max = Flagbearer::EventParser::MAX_EVENT_LENGTH
    assert_raises(ArgumentError) { parse("data: #{'x' * max}\n") }
    assert_raises(ArgumentError) { parse("x" * (max + 1)) }
  end
end
