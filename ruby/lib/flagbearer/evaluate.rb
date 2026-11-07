# frozen_string_literal: true

require "digest"
require "json"

module Flagbearer
  # Pure evaluation: the same rules every Flagbearer SDK follows.
  module Evaluate
    MAX_SAFE_INTEGER = (2**53) - 1
    # Like JavaScript's trim(): Unicode whitespace and the BOM.
    TRIM = /\A[[:space:]\uFEFF]+|[[:space:]\uFEFF]+\z/

    FlagRule = Struct.new(:key, :enabled, :rollout_bps, :override_emails)
    Ruleset = Struct.new(:environment_id, :version, :flags)
    Evaluation = Struct.new(:value, :reason, :version)

    module_function

    # A user's place in a flag's rollout, 0-9999. Below rollout_bps means on.
    def bucket(flag_key, user_key)
      Digest::SHA256.hexdigest(utf8(flag_key) + ":".b + utf8(user_key)).to_i(16) % 10_000
    end

    # The UTF-8 bytes of a string. Binary strings, like HTTP bodies, are taken
    # to be UTF-8 already; other encodings are converted.
    def utf8(text)
      text.encoding == Encoding::BINARY ? text.b : text.encode(Encoding::UTF_8).b
    end

    def text(body) = utf8(body.to_s).force_encoding(Encoding::UTF_8)

    def normalize_email(email)
      text(email).scrub("\uFFFD").gsub(TRIM, "").downcase
    end

    def evaluate(flag, user, version, default)
      return Evaluation.new(default, "fallback", version) if flag.nil?
      return Evaluation.new(false, "off", version) unless flag.enabled

      email = user[:email]
      raise TypeError, "email must be a String" unless email.nil? || email.is_a?(String)

      email = email.nil? ? "" : normalize_email(email)
      if !email.empty? && flag.override_emails.any? { |item| normalize_email(item) == email }
        return Evaluation.new(true, "tester", version)
      end

      key = user[:key]
      raise TypeError, "key must be a String" unless key.nil? || key.is_a?(String)

      if key.nil? || key.empty?
        return Evaluation.new(default, "fallback", version) unless [0, 10_000].include?(flag.rollout_bps)

        return Evaluation.new(flag.rollout_bps == 10_000, "rollout", version)
      end

      Evaluation.new(bucket(flag.key, key) < flag.rollout_bps, "rollout", version)
    end

    # JSON numbers are doubles, so 42.0 is the same version as 42.
    def integer(value)
      return value if value.is_a?(Integer)
      return value.to_i if value.is_a?(Float) && value.finite? && value == value.floor

      nil
    end

    def version(value)
      number = integer(value)
      number if number && number >= 0 && number <= MAX_SAFE_INTEGER
    end

    # The ruleset in a GET /v1/ruleset body, or nil if any of it is invalid.
    def parse_ruleset(body)
      value = JSON.parse(text(body))
      return nil unless value.is_a?(Hash)

      environment_id = value["environmentId"]
      number = version(value["version"])
      flags = value["flags"]
      return nil unless environment_id.is_a?(String) && number && flags.is_a?(Array)

      rules = {}
      flags.each do |item|
        rule = parse_rule(item)
        return nil unless rule

        rules[rule.key] = rule
      end
      Ruleset.new(environment_id, number, rules.freeze).freeze
    rescue JSON::ParserError
      nil
    end

    def parse_rule(item)
      return nil unless item.is_a?(Hash)

      key = item["key"]
      enabled = item["enabled"]
      rollout = integer(item["rolloutBps"])
      emails = item["overrideEmails"]
      return nil unless key.is_a?(String) && [true, false].include?(enabled)
      return nil unless rollout && rollout.between?(0, 10_000)
      return nil unless emails.is_a?(Array) && emails.all?(String)

      FlagRule.new(key, enabled, rollout, emails.freeze).freeze
    end
  end
end
