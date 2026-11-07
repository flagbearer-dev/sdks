# frozen_string_literal: true

# The Ruby SDK behind the same GET /flags and GET /flush routes as node-server.ts.
# Run with: ruby -I ruby/lib testing/servers/ruby-server.rb

require "json"
require "socket"
require "uri"
require "flagbearer"

key = ENV.fetch("FLAGBEARER_SERVER_KEY", "")
abort "The integration fixture requires FLAGBEARER_SERVER_KEY." if key.empty?
url = ENV["FLAGBEARER_URL"].to_s.empty? ? Flagbearer::DEFAULT_URL : ENV["FLAGBEARER_URL"]
$stdout.sync = true

# One client for the whole server, not one per HTTP request.
flags = Flagbearer.init(key, url: url, debug: true)
begin
  flags.sync
  puts "Initial Flagbearer ruleset loaded."
rescue Flagbearer::Error => e
  puts "Initial sync failed; serving defaults while the SDK recovers. #{e.message}"
end

def reply(socket, status, body)
  data = JSON.generate(body)
  socket.write("HTTP/1.1 #{status} X\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n" \
               "Content-Length: #{data.bytesize}\r\nConnection: close\r\n\r\n#{data}")
end

def handle(socket, flags)
  method, target, = socket.gets.to_s.split(" ")
  nil while (line = socket.gets) && line != "\r\n"
  return reply(socket, 405, { error: "Use GET." }) unless method == "GET"

  uri = URI.parse(target.to_s)
  query = URI.decode_www_form(uri.query.to_s).to_h
  case uri.path
  when "/flush"
    # Uploads pending exposures now, so a test sees whether the server took them.
    begin
      flags.flush
      reply(socket, 200, { flushed: true })
    rescue Flagbearer::Error => e
      reply(socket, 502, { error: e.message })
    end
  when "/flags"
    flag = query["flag"].to_s
    fallback = query.fetch("default", "false")
    if flag.empty? || !%w[true false].include?(fallback)
      return reply(socket, 400, { error: "Provide flag, and optionally default=true or default=false." })
    end

    user = { key: query["userKey"], email: query["email"] }.compact
    # Local evaluation: this request does not call Flagbearer's API.
    enabled = flags.enabled?(flag, user, default: fallback == "true")
    reply(socket, 200, { flag: flag, user: user, enabled: enabled })
  else
    reply(socket, 404, { error: "Not found." })
  end
rescue IOError, SystemCallError
  nil
ensure
  socket.close
end

server = TCPServer.new("127.0.0.1", Integer(ENV.fetch("PORT", "0")))
shutdown = lambda do |_signal|
  # Signal handlers can't take locks, so the close runs on its own thread.
  Thread.new do
    flags.close
    exit!(0)
  end
end
Signal.trap("TERM", &shutdown)
Signal.trap("INT", &shutdown)
puts "Ruby SDK fixture listening on http://127.0.0.1:#{server.addr[1]}"
loop { Thread.new(server.accept) { |socket| handle(socket, flags) } }
