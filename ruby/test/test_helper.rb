# frozen_string_literal: true

require "minitest/autorun"
require "json"
require "socket"
require "flagbearer"

# Short enough that retries finish within a test, long enough that nothing
# polls unless a test asks for it.
FAST = Flagbearer::Timings.new(
  request_timeout: 1.0, poll_interval: 3600.0, retry_base: 0.01, max_retry: 0.05,
  max_version_refresh_delay: 0.0, stream_connect_timeout: 1.0, stream_idle_timeout: 5.0,
  exposure_flush_interval: 3600.0, exposure_timeout: 1.0
).freeze

def rule(key, enabled: true, rollout_bps: 10_000, override_emails: [])
  { "key" => key, "enabled" => enabled, "rolloutBps" => rollout_bps, "overrideEmails" => override_emails }
end

# The SDK server's three routes on a real socket. Tests set what it returns and
# read what it got.
class FakeSdk
  attr_accessor :version, :environment_id, :flags, :ruleset_body, :ruleset_statuses,
                :stream_status, :exposure_statuses
  attr_reader :ruleset_requests, :exposures, :stream_connections, :stream_opened, :url

  def initialize
    @version = 1
    @environment_id = "env-1"
    @flags = [rule("checkout")]
    @ruleset_body = nil
    @ruleset_statuses = []
    @stream_status = 200
    @exposure_statuses = []
    @ruleset_requests = []
    @exposures = []
    @stream_connections = 0
    @events = Thread::Queue.new
    @stream_opened = Thread::Queue.new
    @lock = Mutex.new
    @stopping = false
    @server = TCPServer.new("127.0.0.1", 0)
    @url = "http://127.0.0.1:#{@server.addr[1]}"
    @clients = []
    @thread = Thread.new { accept }
  end

  def synchronize(&) = @lock.synchronize(&)

  def announce(version)
    @events << "event: version\ndata: {\"version\":#{version}}\n\n"
  end

  def wait_for_stream(timeout = 2) = @stream_opened.pop(timeout: timeout)

  def stop
    @stopping = true
    @server.close
    @clients.each { |socket| socket.close rescue nil } # rubocop:disable Style/RescueModifier
  end

  private

  def accept
    loop do
      socket = @server.accept
      @clients << socket
      Thread.new(socket) { |client| handle(client) }
    end
  rescue IOError, SystemCallError
    nil
  end

  def handle(socket)
    request_line = socket.gets or return
    method, path, = request_line.split(" ")
    headers = {}
    while (line = socket.gets) && line != "\r\n"
      name, value = line.split(":", 2)
      headers[name.downcase] = value.strip
    end
    body = headers["content-length"] ? socket.read(headers["content-length"].to_i) : nil
    return respond(socket, 401, "{}") unless headers["authorization"] == "Bearer fbs_test"

    case [method, path]
    in ["GET", "/v1/ruleset"] then ruleset(socket, headers)
    in ["GET", "/v1/stream"] then stream(socket)
    in ["POST", "/v1/exposures"] then exposure(socket, body)
    else respond(socket, 404, "{}")
    end
  rescue IOError, SystemCallError
    nil
  ensure
    socket.close rescue nil # rubocop:disable Style/RescueModifier
  end

  def respond(socket, status, body, extra = {})
    head = ["HTTP/1.1 #{status} X", "Content-Type: application/json", "Content-Length: #{body.bytesize}",
            "Connection: close", *extra.map { |name, value| "#{name}: #{value}" }]
    socket.write("#{head.join("\r\n")}\r\n\r\n#{body}")
  end

  def ruleset(socket, headers)
    status, etag, body = synchronize do
      @ruleset_requests << headers["if-none-match"]
      chosen = @ruleset_statuses.shift || 200
      document = @ruleset_body || JSON.generate(
        "environmentId" => @environment_id, "version" => @version, "flags" => @flags
      )
      [chosen, %("#{@version}"), document]
    end
    return respond(socket, status, "{}", "Retry-After" => "0") unless status == 200
    return respond(socket, 304, "", "ETag" => etag) if headers["if-none-match"] == etag

    respond(socket, 200, body, "ETag" => etag)
  end

  def stream(socket)
    status, version = synchronize do
      @stream_connections += 1
      [@stream_status, @version]
    end
    return respond(socket, status, "{}") unless status == 200

    socket.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" \
                 "Connection: close\r\n\r\n")
    socket.write("retry: 5000\n\nevent: version\ndata: {\"version\":#{version}}\n\n")
    @stream_opened << true
    until @stopping
      event = @events.pop(timeout: 0.05)
      socket.write(event) if event
    end
  end

  def exposure(socket, body)
    upload = JSON.parse(body)
    status = synchronize do
      @exposures << upload
      @exposure_statuses.shift || 200
    end
    return respond(socket, status, "{}") unless status == 200

    respond(socket, 200, JSON.generate("accepted" => upload["events"].size, "rejectedChecks" => 0))
  end
end

module ClientHelpers
  def setup
    @sdk = FakeSdk.new
    @clients = []
  end

  def teardown
    @clients.each(&:close)
    @sdk.stop
  end

  def make_client(timings: FAST, **options)
    client = Flagbearer::Client.new("fbs_test", url: @sdk.url, timings: timings, **options)
    @clients << client
    client
  end

  # Polls a condition the SDK reaches on its own threads.
  def wait_for(timeout: 3)
    deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
    until yield
      flunk "condition not met in time" if Process.clock_gettime(Process::CLOCK_MONOTONIC) > deadline
      sleep 0.005
    end
  end
end
