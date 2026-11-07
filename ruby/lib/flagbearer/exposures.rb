# frozen_string_literal: true

require "json"
require "securerandom"

module Flagbearer
  # Counts flag checks and uploads them in the background.
  class ExposureReporter
    WINDOW_MS = 30_000
    MAX_RECORDS = 1000
    BATCH_SIZE = 100
    MAX_CHECK_COUNT = 1_000_000_000

    # One upload, fixed when sealed, so a retry sends exactly the same body and
    # the server can recognize it by its batch ID.
    Batch = Struct.new(:id, :user, :events)

    def initialize(endpoint:, key:, app_version:, timings:)
      @endpoint = endpoint
      @key = key
      @app_version = app_version && !app_version.empty? ? app_version[0, 100] : nil
      @timeout = timings.exposure_timeout
      @reporter_id = SecureRandom.uuid
      @lock = Mutex.new
      @send_lock = Mutex.new # one upload at a time; flush waits for the one in progress
      @wake = ConditionVariable.new
      @active = {}
      @sealed = []
      @sealed_events = 0
      @dropped = 0
      @reported_dropped = 0
      @retry_at = 0.0
      @failures = 0
      @closed = false
      @thread = Thread.new { run(timings.exposure_flush_interval) }
      @thread.name = "flagbearer-exposures"
      @thread.report_on_exception = false
    end

    def record(flag, user, details)
      @lock.synchronize do
        return if @closed

        key = user[:key]
        if !flag.is_a?(String) || flag.empty? || HTTP.js_length(flag) > 256 ||
           !(key.nil? || key.is_a?(String)) || (key && HTTP.js_length(key) > 256)
          @dropped += 1
          return
        end
        now = (Time.now.to_r * 1000).floor
        bucket = iso(now / WINDOW_MS * WINDOW_MS)
        raw_email = user[:email]
        email = if raw_email.is_a?(String) && !raw_email.empty? && HTTP.js_length(raw_email) <= 254
                  normalized = Evaluate.normalize_email(raw_email)
                  normalized.empty? ? nil : normalized
                end
        user_key = key.nil? || key.empty? ? nil : key
        identity = [flag, user_key, email, details.value, details.reason, details.version, bucket]
        existing = @active[identity]
        if existing
          if existing[:event]["checkCount"] < MAX_CHECK_COUNT
            existing[:event]["checkCount"] += 1
            existing[:event]["lastCheckedAt"] = iso(now)
          else
            @dropped += 1
          end
          return
        end
        if @active.size + @sealed_events >= MAX_RECORDS
          @dropped += 1
          return
        end
        who = {}
        who["key"] = user_key if user_key
        who["email"] = email if email
        @active[identity] = {
          user: who,
          event: {
            "flagKey" => flag, "result" => details.value, "reason" => details.reason,
            "configurationVersion" => details.version, "bucket" => bucket, "checkCount" => 1,
            "firstCheckedAt" => iso(now), "lastCheckedAt" => iso(now)
          }
        }
      end
    rescue StandardError
      @lock.synchronize { @dropped += 1 }
    end

    # Uploads every pending check. Raises when an upload fails.
    def flush
      @send_lock.synchronize do
        send_all
      rescue StandardError
        @lock.synchronize do
          @failures += 1
          delay = [60.0, 2.0**[@failures, 6].min].min
          @retry_at = [@retry_at, monotonic + delay].max
        end
        raise
      end
    end

    def close
      @lock.synchronize do
        return if @closed

        @closed = true
        @wake.broadcast
      end
      flush
    end

    # Stops the timer without uploading: for a forked child, whose parent still
    # owns these checks.
    def abandon
      @lock.synchronize do
        @closed = true
        @wake.broadcast
      end
    end

    private

    def monotonic = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    def iso(milliseconds)
      Time.at(Rational(milliseconds, 1000)).utc.strftime("%Y-%m-%dT%H:%M:%S.%LZ")
    end

    def run(interval)
      loop do
        @lock.synchronize do
          return if @closed

          @wake.wait(@lock, HTTP.jitter(interval, 0.1))
          return if @closed
        end
        due = @lock.synchronize { monotonic >= @retry_at }
        next unless due

        begin
          flush
        rescue StandardError
          nil # background uploads retry later
        end
      end
    end

    # Expects @lock held. A batch never mixes users.
    def seal
      open = {}
      @active.each_value do |item|
        batch = open[item[:user]]
        if batch.nil? || batch.events.size >= BATCH_SIZE
          batch = Batch.new(SecureRandom.uuid, item[:user], [])
          open[item[:user]] = batch
          @sealed << batch
        end
        batch.events << item[:event]
        @sealed_events += 1
      end
      @active = {}
    end

    def settle(batch)
      @lock.synchronize do
        if @sealed.first.equal?(batch)
          @sealed.shift
          @sealed_events -= batch.events.size
        end
      end
    end

    def send_all
      @lock.synchronize { seal }
      loop do
        batch, dropped = @lock.synchronize do
          next nil if @sealed.empty? && @dropped <= @reported_dropped

          # Only lost checks to report: an empty batch carries the count.
          [@sealed.first || Batch.new(SecureRandom.uuid, {}, []), @dropped]
        end
        break if batch.nil?

        checks = batch.events.sum { |event| event["checkCount"] }
        body = {
          "batchId" => batch.id, "reporterId" => @reporter_id, "user" => batch.user,
          "events" => batch.events, "droppedChecks" => dropped,
          "sdk" => "ruby", "sdkVersion" => VERSION
        }
        body["appVersion"] = @app_version if @app_version
        request = Net::HTTP::Post.new(@endpoint.path("/v1/exposures"), {
          "Authorization" => "Bearer #{@key}", "Content-Type" => "application/json"
        })
        request.body = JSON.generate(body)
        response = HTTP.request(@endpoint, request, timeout: @timeout)
        status = response.code.to_i
        unless status.between?(200, 299)
          refused = status.between?(400, 499) && status != 429
          @lock.synchronize do
            wait = HTTP.retry_after(response["Retry-After"])
            @retry_at = [@retry_at, monotonic + [wait, 3600].min].max if wait.positive?
            if refused
              # The server won't take this batch, so retrying can't help.
              @dropped += checks
              @retry_at = monotonic + 60
            end
          end
          settle(batch) if refused
          raise Error.new("Exposure upload failed (#{status})", retryable: !refused)
        end
        rejected = receipt(response.body, checks, batch.events.size)
        @lock.synchronize do
          @dropped += rejected
          @reported_dropped = [@reported_dropped, dropped].max
        end
        settle(batch)
      end
      @lock.synchronize { @failures = 0 }
    end

    def receipt(body, checks, events)
      parsed = JSON.parse(Evaluate.text(body))
      accepted = parsed.is_a?(Hash) ? parsed["accepted"] : nil
      rejected = parsed.is_a?(Hash) ? parsed["rejectedChecks"] : nil
      if accepted.is_a?(Integer) && accepted.between?(0, events) &&
         rejected.is_a?(Integer) && rejected.between?(0, checks)
        return rejected
      end

      raise Error.new("Invalid exposure upload receipt", retryable: true)
    rescue JSON::ParserError
      raise Error.new("Invalid exposure upload receipt", retryable: true)
    end
  end
end
