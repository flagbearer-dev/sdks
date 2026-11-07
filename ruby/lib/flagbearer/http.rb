# frozen_string_literal: true

require "net/http"
require "openssl"
require "time"
require "uri"

module Flagbearer
  # A failed request or an unusable response. Retryable failures back off and
  # try again; permanent ones stop automatic refreshes until sync is called.
  class Error < StandardError
    attr_reader :retry_after

    def initialize(message, retryable: false, retry_after: 0.0)
      super(message)
      @retryable = retryable
      @retry_after = retry_after
    end

    def retryable? = @retryable
    def permanent? = !@retryable
  end

  # Raised by sync after close.
  class ClosedError < StandardError
    def initialize(message = "Flagbearer client is closed") = super
  end

  # Network plumbing shared by the ruleset, stream and exposure requests.
  module HTTP
    MAX_RETRY = 30.0

    module_function

    # Seconds to wait from a Retry-After header, given in seconds or as an HTTP date.
    def retry_after(header, now = Time.now)
      return 0.0 if header.nil? || header.empty?

      seconds = Float(header, exception: false)
      return seconds.finite? && seconds.positive? ? seconds : 0.0 if seconds

      [Time.httpdate(header) - now, 0.0].max
    rescue ArgumentError
      0.0
    end

    def check_status(response)
      status = response.code.to_i
      return if status.between?(200, 299)

      # A 3xx lands here too: Net::HTTP never follows redirects.
      raise Error.new(
        "Flagbearer request failed: #{status}",
        retryable: [408, 429].include?(status) || status >= 500,
        retry_after: retry_after(response["Retry-After"])
      )
    end

    def backoff(attempt, base: 1.0, cap: MAX_RETRY)
      delay = [cap, base * (2**[attempt, 5].min)].min
      [cap, delay * (0.8 + (rand * 0.4))].min
    end

    def jitter(interval, spread)
      interval * (1 - spread + (rand * 2 * spread))
    end

    # UTF-16 code units, as JavaScript and the server's limits count them.
    def js_length(text)
      Evaluate.text(text).encode("UTF-16LE").bytesize / 2
    end

    # One SDK server address.
    class Endpoint
      attr_reader :uri

      def initialize(url)
        @uri = URI.parse(url.to_s)
        raise ArgumentError, "Flagbearer url must use HTTP or HTTPS" unless
          %w[http https].include?(@uri.scheme) && @uri.host && !@uri.host.empty?

        @prefix = @uri.path.to_s.chomp("/")
      rescue URI::InvalidURIError
        raise ArgumentError, "Flagbearer url must use HTTP or HTTPS"
      end

      def path(route) = "#{@prefix}#{route}"

      def connection(open_timeout:, read_timeout:)
        http = Net::HTTP.new(@uri.host, @uri.port)
        http.use_ssl = @uri.scheme == "https"
        http.open_timeout = open_timeout
        http.read_timeout = read_timeout
        http.write_timeout = read_timeout
        http.keep_alive_timeout = 0
        http
      end
    end

    NETWORK_ERRORS = [
      IOError, SystemCallError, SocketError, Timeout::Error, OpenSSL::SSL::SSLError,
      Net::HTTPBadResponse, Net::ProtocolError, EOFError
    ].freeze

    # Sends one request and reads the whole response. Network errors are retryable.
    # on_connection receives the started connection, so close can abort it.
    def request(endpoint, request, timeout:, on_connection: nil)
      http = endpoint.connection(open_timeout: timeout, read_timeout: timeout)
      http.start
      on_connection&.call(http)
      response = http.request(request)
      response.body
      response
    rescue *NETWORK_ERRORS => e
      raise Error.new("Flagbearer request failed: #{e.message}", retryable: true)
    ensure
      begin
        http&.finish if http&.started?
      rescue IOError
        nil
      end
    end
  end
end
