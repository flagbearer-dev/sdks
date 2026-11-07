# frozen_string_literal: true

require "json"

module Flagbearer
  # Server-Sent Events, fed text in arbitrary pieces. Calls on_version for each
  # well-formed version event and ignores everything else.
  class EventParser
    MAX_EVENT_LENGTH = 65_536

    def initialize(&on_version)
      @on_version = on_version
      @buffer = "".b
      @event = "message"
      @data = +""
    end

    # Works on bytes: CR and LF never occur inside a multi-byte UTF-8 character,
    # so a character split across reads is whole again by the time its line ends.
    def feed(chunk)
      @buffer << chunk.b
      start = 0
      while (index = @buffer.index(/[\r\n]/n, start))
        # A CR at the end might be the first half of a CRLF in the next piece.
        break if @buffer.getbyte(index) == 13 && index == @buffer.bytesize - 1

        line(@buffer.byteslice(start, index - start).force_encoding(Encoding::UTF_8).scrub("\uFFFD"))
        index += 1 if @buffer.getbyte(index) == 13 && @buffer.getbyte(index + 1) == 10
        start = index + 1
      end
      @buffer = @buffer.byteslice(start..)
      raise ArgumentError, "Flagbearer stream line too large" if @buffer.bytesize > MAX_EVENT_LENGTH
    end

    private

    def line(text)
      if text.empty?
        dispatch
        return
      end
      return if text.start_with?(":")

      field, separator, content = text.partition(":")
      content = "" if separator.empty?
      content = content[1..] if content.start_with?(" ")
      case field
      when "event" then @event = content
      when "data"
        @data << content << "\n"
        raise ArgumentError, "Flagbearer stream event too large" if @data.length > MAX_EVENT_LENGTH
      end
    end

    def dispatch
      if @event == "version" && !@data.empty?
        parsed = begin
          JSON.parse(@data.chomp("\n"))
        rescue JSON::ParserError
          nil
        end
        version = parsed.is_a?(Hash) ? Evaluate.version(parsed["version"]) : nil
        @on_version.call(version) if version
      end
      @event = "message"
      @data = +""
    end
  end

  # Holds GET /v1/stream open on its own thread and reconnects with backoff.
  class VersionStream
    def initialize(endpoint:, headers:, timings:, debug:, on_version:, on_open:, on_disconnect:)
      @endpoint = endpoint
      @headers = headers.merge("Accept" => "text/event-stream")
      @timings = timings
      @debug = debug
      @on_version = on_version
      @on_open = on_open
      @on_disconnect = on_disconnect
      @lock = Mutex.new
      @wake = ConditionVariable.new
      @closed = false
      @http = nil
      @attempts = 0
      @thread = Thread.new { run }
      @thread.name = "flagbearer-stream"
      @thread.report_on_exception = false
    end

    attr_reader :thread

    def close
      http = @lock.synchronize do
        return if @closed

        @closed = true
        @wake.broadcast
        @http
      end
      @debug.call("Stream closed")
      abort(http)
    end

    private

    def closed? = @lock.synchronize { @closed }

    # Closing the socket from another thread wakes a read blocked on it.
    def abort(http)
      http&.finish if http&.started?
    rescue IOError, SystemCallError
      nil
    end

    def run
      until closed?
        failure = connect
        return if closed?

        @on_disconnect.call
        if failure.is_a?(Error) && failure.permanent?
          # Polling remains available; the next ruleset request also checks the key.
          Flagbearer.warn_log("stream stopped; using polling: #{failure.message}")
          return
        end
        delay = HTTP.backoff(@attempts, base: @timings.retry_base, cap: @timings.max_retry)
        @attempts += 1
        delay = [delay, failure.retry_after].max if failure.is_a?(Error)
        @debug.call("Stream reconnect scheduled", delayMs: (delay * 1000).round)
        @lock.synchronize { @wake.wait(@lock, delay) unless @closed }
      end
    end

    def connect
      @debug.call("Stream connecting")
      http = @endpoint.connection(
        open_timeout: @timings.stream_connect_timeout,
        read_timeout: @timings.stream_connect_timeout
      )
      http.start
      @lock.synchronize do
        return nil if @closed

        @http = http
      end
      request = Net::HTTP::Get.new(@endpoint.path("/v1/stream"), @headers)
      failure = nil
      http.request(request) do |response|
        HTTP.check_status(response)
        type = response["Content-Type"].to_s.split(";").first.to_s.strip.downcase
        raise Error.new("Invalid Flagbearer stream response") unless type == "text/event-stream"
        return nil if closed?

        # From here the timeout is the idle limit: heartbeats arrive every 15 seconds.
        http.read_timeout = @timings.stream_idle_timeout
        @on_open.call

        parser = EventParser.new { |version| @on_version.call(version) }
        response.read_body do |chunk|
          @attempts = 0
          parser.feed(chunk)
        end
      end
      failure
    rescue StandardError => e
      e
    ensure
      @lock.synchronize { @http = nil }
      abort(http)
    end
  end
end
