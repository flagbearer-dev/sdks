# frozen_string_literal: true

module Flagbearer
  # Every delay the client uses, in seconds. Tests shorten them.
  Timings = Struct.new(
    :request_timeout, :poll_interval, :retry_base, :max_retry,
    :max_version_refresh_delay, :stream_connect_timeout, :stream_idle_timeout,
    :exposure_flush_interval, :exposure_timeout,
    keyword_init: true
  ) do
    def self.default
      new(
        request_timeout: 5.0, poll_interval: 30.0, retry_base: 1.0, max_retry: 30.0,
        max_version_refresh_delay: 2.0, stream_connect_timeout: 5.0, stream_idle_timeout: 45.0,
        exposure_flush_interval: 30.0, exposure_timeout: 10.0
      )
    end
  end

  # Decides when to fetch the ruleset. One worker thread makes every fetch, so
  # there is never more than one in flight.
  class Coordinator
    def initialize(fetch:, apply:, on_permanent_failure:, timings:, debug:, version: nil)
      @fetch = fetch
      @apply = apply
      @on_permanent_failure = on_permanent_failure
      @timings = timings
      @debug = debug
      @lock = Mutex.new
      @wake = ConditionVariable.new

      @stopped = false
      @blocked = false
      @streaming = false
      @version = version
      @wanted = nil
      @attempt = 0
      @not_before = 0.0 # nothing is fetched before this: backoff or a server's Retry-After
      @retry_at = nil
      @poll_at = nil
      @version_at = nil
      @requested = false
      @in_flight = false
      @waiters = []

      @lock.synchronize { ensure_polling }
      @thread = Thread.new { run }
      @thread.name = "flagbearer-refresh"
      @thread.report_on_exception = false
    end

    # Waits for a fetch. Joins one already in flight.
    def sync(timeout: nil)
      waiter = Thread::Queue.new
      @lock.synchronize do
        raise ClosedError if @stopped

        @blocked = false
        ensure_polling
        @waiters << waiter
        if @in_flight
          @debug.call("Joining active refresh")
        else
          @retry_at = nil
          @requested = true
        end
        @wake.broadcast
      end
      result = waiter.pop(timeout: timeout)
      raise Error.new("Flagbearer sync timed out", retryable: true) if result.nil?
      raise result unless result == :ok

      nil
    end

    def refresh_in_background
      @lock.synchronize { background }
    end

    def on_version(version)
      @lock.synchronize do
        next if @stopped

        @debug.call("Stream version received", version: version, currentVersion: @version)
        @wanted = [@wanted || 0, version].max
        if behind? && @version_at.nil? && !@in_flight
          @version_at = now + (rand * @timings.max_version_refresh_delay)
          @wake.broadcast
        end
      end
    end

    def streaming(connected, refresh: true)
      @lock.synchronize do
        next if @stopped

        if @streaming != connected
          @debug.call(connected ? "Stream connected" : "Stream disconnected; using polling")
        end
        @streaming = connected
        if connected
          clear_polling
          background if refresh
        else
          ensure_polling
        end
        @wake.broadcast
      end
    end

    def close
      waiters = @lock.synchronize do
        return if @stopped

        @stopped = true
        @debug.call("Coordinator closed")
        @wake.broadcast
        taken = @waiters
        @waiters = []
        taken
      end
      waiters.each { |waiter| waiter << ClosedError.new }
    end

    private

    # The helpers below expect @lock to be held.

    def now = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    def behind? = !@wanted.nil? && (@version.nil? || @version < @wanted)

    def background
      return if @stopped || @blocked || @retry_at || @in_flight

      @requested = true
      @wake.broadcast
    end

    def ensure_polling
      return if @stopped || @blocked || @streaming || @poll_at

      interval = HTTP.jitter(@timings.poll_interval, 0.1)
      @debug.call("Polling scheduled", intervalMs: (interval * 1000).round)
      @poll_at = now + interval
    end

    def clear_polling
      @debug.call("Polling disabled") if @poll_at
      @poll_at = nil
    end

    def schedule_retry(error)
      delay = HTTP.backoff(@attempt, base: @timings.retry_base, cap: @timings.max_retry)
      @attempt += 1
      delay = [delay, error.retry_after].max if error.is_a?(Error)
      @not_before = now + delay
      @retry_at = @not_before
      @debug.call("Refresh retry scheduled", delayMs: (delay * 1000).round, attempt: @attempt)
    end

    # Seconds until the worker has something to do, or nil if never.
    def next_due(at)
      return [@not_before - at, 0].max if @requested

      times = [@retry_at, @poll_at, @version_at].compact
      times.empty? ? nil : [times.min - at, 0].max
    end

    # Turns any timer that has fired into a fetch request.
    def take_due(at)
      if @version_at && @version_at <= at
        @version_at = nil
        background if behind?
      end
      if @retry_at && @retry_at <= at
        @retry_at = nil
        background
      end
      if @poll_at && @poll_at <= at
        @poll_at = nil
        background
        ensure_polling
      end
      @requested && at >= @not_before
    end

    def run
      loop do
        waiters = @lock.synchronize do
          until @stopped
            at = now
            break if take_due(at)

            @wake.wait(@lock, next_due(at))
          end
          return if @stopped

          @requested = false
          @in_flight = true
          @version_at = nil
          taken = @waiters
          @waiters = []
          taken
        end

        error = nil
        begin
          refresh
        rescue StandardError => e
          error = e
        end

        stopped = @lock.synchronize do
          @in_flight = false
          # Sync calls that arrived during this fetch join it.
          waiters.concat(@waiters)
          @waiters = []
          @stopped
        end
        waiters.each { |waiter| waiter << (error || :ok) }
        Flagbearer.warn_log("sync failed: #{error.message}") if error && !stopped && waiters.empty?
      end
    end

    # One refresh, repeated while a stream announcement is still ahead of us.
    def refresh
      loop do
        @debug.call("Refresh started")
        begin
          result = @fetch.call
        rescue StandardError => e
          permanent = @lock.synchronize do
            raise if @stopped

            if e.is_a?(Error) && e.permanent?
              @debug.call("Automatic refresh stopped after permanent failure")
              @blocked = true
              @streaming = false
              @retry_at = nil
              clear_polling
              true
            else
              schedule_retry(e)
              false
            end
          end
          @on_permanent_failure.call if permanent
          raise
        end

        again = @lock.synchronize do
          next false if @stopped

          if result.nil?
            @debug.call("Snapshot unchanged", version: @version)
            if behind?
              # The backend is still behind: retry later rather than spin.
              schedule_retry(nil)
            else
              @attempt = 0
              @not_before = 0.0
            end
            next false
          end

          previous = @version
          if previous.nil? || result.version >= previous
            @version = result.version
            @apply.call(result)
            @debug.call("Snapshot applied", version: result.version, previousVersion: previous)
          else
            @debug.call("Older snapshot ignored", version: result.version, currentVersion: previous)
          end

          if !behind?
            @attempt = 0
            @not_before = 0.0
            false
          elsif previous && result.version <= previous
            schedule_retry(nil)
            false
          else
            true # An announcement arrived during the request: fetch again now.
          end
        end
        return unless again
      end
    end
  end
end
