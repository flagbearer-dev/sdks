# frozen_string_literal: true

require "test_helper"

class TestFixtures < Minitest::Test
  FIXTURES = JSON.parse(File.read(File.expand_path("../../fixtures/evaluation.json", __dir__), encoding: "UTF-8"))
  E = Flagbearer::Evaluate

  FIXTURES["buckets"].each_with_index do |item, index|
    define_method("test_bucket_#{index}_#{item['flag']}") do
      assert_equal item["bucket"], E.bucket(item["flag"], item["user"])
    end
  end

  FIXTURES["cases"].each_with_index do |item, index|
    define_method("test_case_#{index}_#{item['name'].tr(' ,', '__')}") do
      flag = nil
      if item["flag"]
        ruleset = E.parse_ruleset(JSON.generate("environmentId" => "1", "version" => 7, "flags" => [item["flag"]]))
        refute_nil ruleset
        flag = ruleset.flags[item["flag"]["key"]]
      end
      user = { key: item["user"]["key"], email: item["user"]["email"] }
      result = E.evaluate(flag, user, 7, item["default"])
      assert_equal [item["expect"]["value"], item["expect"]["reason"], 7], [result.value, result.reason, result.version]
    end
  end

  def test_invalid_rules_reject_the_whole_ruleset
    good = rule("b", rollout_bps: 0)
    [
      rule("a", rollout_bps: 10_001), rule("a").merge("rolloutBps" => 50.5),
      rule("a").merge("rolloutBps" => true), rule("a").merge("enabled" => 1),
      rule("a").merge("overrideEmails" => [1]), rule("a").except("overrideEmails")
    ].each do |flag|
      body = JSON.generate("environmentId" => "1", "version" => 1, "flags" => [good, flag])
      assert_nil E.parse_ruleset(body), flag.inspect
    end
    assert_nil E.parse_ruleset('{"environmentId":"1","version":1,"flags":[]} trailing')
    refute_nil E.parse_ruleset('{"environmentId":"1","version":1.0,"flags":[]}')
  end

  def test_trimming_matches_javascript
    flag = E::FlagRule.new("a", true, 0, ["\u00A0ann@example.com\u00A0"])
    assert_equal "tester", E.evaluate(flag, { key: "u", email: "ann@example.com" }, 1, false).reason
  end

  def test_keys_in_other_encodings_hash_as_utf8
    utf8 = E.bucket("checkout", "ü-user")
    assert_equal 6665, utf8
    assert_equal utf8, E.bucket("checkout", "ü-user".encode("ISO-8859-1"))
    assert_equal utf8, E.bucket("checkout", "ü-user".b)
    assert_equal utf8, E.bucket("checkout".encode("US-ASCII"), "ü-user")
  end
end
