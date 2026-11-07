# frozen_string_literal: true

module Flagbearer
  # Diagnostics for debug: true. Never logs keys, user data, flag names or rules.
  class DebugLog
    def initialize(enabled, io: $stderr)
      @enabled = enabled
      @io = io
    end

    def call(message, **details)
      return unless @enabled

      if details.empty?
        @io.puts("[Flagbearer] #{message}")
      else
        # Same shape as the Node SDK's console.debug output: { version: 42 }.
        fields = details.map { |name, value| "#{name}: #{value.nil? ? 'undefined' : value}" }
        @io.puts("[Flagbearer] #{message} { #{fields.join(', ')} }")
      end
    rescue StandardError
      nil
    end
  end

  def self.warn_log(message)
    $stderr.puts("Flagbearer: #{message}")
  rescue StandardError
    nil
  end
end
