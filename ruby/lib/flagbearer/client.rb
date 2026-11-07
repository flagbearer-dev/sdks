# frozen_string_literal: true

module Flagbearer
  # Checks flags against the rules in memory. Create one per process with
  # Flagbearer.init and keep it for the life of the process. Thread-safe.
  class Client
    def initialize(key, url: DEFAULT_URL, debug: false, app_version: nil, timings: Timings.default)
      raise ArgumentError, "A nonempty server SDK key is required" unless
        key.is_a?(String) && !key.empty? && !key.match?(/[[:space:]]/)

      @endpoint = HTTP::Endpoint.new(url)
      @key = key
      @headers = { "Authorization" => "Bearer #{key}" }.freeze
      @app_version = app_version
      @timings = timings
      @debug = DebugLog.new(debug)
      @ruleset = nil
      @active = nil
      @closed = false
      @lock = Mutex.new
      start
    end

    # Waits for the latest rules. Raises Flagbearer::Error when the request fails
    # or the timeout passes; the client keeps retrying in the background either way.
    def sync(timeout: nil)
      check_fork
      @coordinator.sync(timeout: timeout)
    end

    # Checks a flag using only the rules in memory. Never raises or blocks on the
    # network. +user+ is a Hash with :key and :email, as symbols or strings.
    def enabled?(flag, user = {}, default: false)
      check_fork
      fields = user_fields(user)
      ruleset = @ruleset
      version = ruleset&.version
      details = begin
        Evaluate.evaluate(ruleset&.flags&.[](flag), fields, version, default)
      rescue StandardError
        Evaluate::Evaluation.new(default, "fallback", version)
      end
      @reporter.record(flag, fields, details)
      details.value
    end

    # Uploads pending exposure reports. Raises Flagbearer::Error when an upload fails.
    def flush
      check_fork
      @reporter.flush
    end

    # Stops streaming and background threads, then uploads pending exposures.
    # Safe to call more than once. Rules in memory stay usable.
    def close
      @lock.synchronize do
        return if @closed

        @closed = true
        if Process.pid != @pid
          # A forked child that never restarted: its parent owns these checks.
          @reporter.abandon
          return
        end
      end
      @coordinator.close
      @stream.close
      abort(@active)
      begin
        @reporter.close
      rescue StandardError
        nil # shutdown continues if the last upload fails
      end
      nil
    end

    # Restarts background work in a forked child. The client also does this on
    # its own the first time it's used after a fork; call it from a hook like
    # Puma's on_worker_boot to start streaming before the first request.
    def postfork
      @lock.synchronize do
        return if @closed || Process.pid == @pid

        @debug.call("Restarting after fork")
        # The parent's threads don't exist here, and its sockets are still the
        # parent's, so its objects are dropped rather than closed. The child
        # keeps the rules and starts with no pending checks: the parent reports
        # its own.
        @active = nil
        start
      end
      nil
    end

    private

    def start
      @pid = Process.pid
      connected = false
      @reporter = ExposureReporter.new(
        endpoint: @endpoint, key: @key, app_version: @app_version, timings: @timings
      )
      stream = nil
      coordinator = Coordinator.new(
        fetch: -> { fetch },
        apply: ->(ruleset) { @ruleset = ruleset },
        on_permanent_failure: -> { stream&.close },
        timings: @timings, debug: @debug, version: @ruleset&.version
      )
      stream = VersionStream.new(
        endpoint: @endpoint, headers: @headers, timings: @timings, debug: @debug,
        on_version: ->(version) { coordinator.on_version(version) },
        on_open: lambda {
          # After a reconnect, refetch in case an announcement was missed.
          coordinator.streaming(true, refresh: connected)
          connected = true
        },
        on_disconnect: -> { coordinator.streaming(false) }
      )
      @coordinator = coordinator
      @stream = stream
      coordinator.refresh_in_background
    end

    def check_fork
      postfork if Process.pid != @pid
    end

    def user_fields(user)
      return { key: nil, email: nil } if user.nil?
      # Anything else gets the default, and its check is counted as lost.
      return { key: user, email: nil } unless user.is_a?(Hash)

      { key: user.fetch(:key) { user["key"] }, email: user.fetch(:email) { user["email"] } }
    end

    def abort(http)
      http&.finish if http&.started?
    rescue IOError, SystemCallError
      nil
    end

    def fetch
      current = @ruleset
      headers = @headers.dup
      headers["If-None-Match"] = %("#{current.version}") if current
      request = Net::HTTP::Get.new(@endpoint.path("/v1/ruleset"), headers)
      response = begin
        HTTP.request(@endpoint, request, timeout: @timings.request_timeout,
                                         on_connection: ->(http) { @active = http })
      ensure
        @active = nil
      end
      return nil if response.code.to_i == 304

      HTTP.check_status(response)
      ruleset = Evaluate.parse_ruleset(response.body.to_s)
      raise Error.new("Invalid ruleset response") unless ruleset
      if current && ruleset.environment_id != current.environment_id
        raise Error.new("Ruleset environment changed unexpectedly")
      end

      ruleset
    end
  end
end
