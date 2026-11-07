#include <doctest/doctest.h>

#include <fstream>
#include <sstream>

#include "internal.hpp"

using namespace flagbearer::detail;

namespace {

Json load_fixtures() {
  std::ifstream file(FLAGBEARER_FIXTURES);
  std::stringstream text;
  text << file.rdbuf();
  auto parsed = parse_json(text.str());
  REQUIRE(parsed);
  return *parsed;
}

flagbearer::User user_from(const Json& value) {
  flagbearer::User user;
  if (const auto* key = value.get("key")) user.key = *key->as_string();
  if (const auto* email = value.get("email")) user.email = *email->as_string();
  return user;
}

}  // namespace

TEST_CASE("shared fixture buckets") {
  auto fixtures = load_fixtures();
  for (const auto& item : *fixtures.get("buckets")->as_array()) {
    const auto& flag = *item.get("flag")->as_string();
    const auto& user = *item.get("user")->as_string();
    CAPTURE(flag);
    CHECK(bucket(flag, user) == *item.get("bucket")->as_integer());
  }
}

TEST_CASE("shared fixture cases") {
  auto fixtures = load_fixtures();
  for (const auto& item : *fixtures.get("cases")->as_array()) {
    const auto& name = *item.get("name")->as_string();
    CAPTURE(name);
    std::optional<Ruleset> ruleset;
    const FlagRule* rule = nullptr;
    const auto* flag = item.get("flag");
    if (!std::holds_alternative<std::nullptr_t>(flag->value)) {
      // Rebuild the flag's JSON through the ruleset parser, as the SDK reads it.
      const auto& f = *flag;
      std::string emails = "[";
      for (const auto& email : *f.get("overrideEmails")->as_array()) {
        if (emails.size() > 1) emails += ",";
        emails += json_quote(*email.as_string());
      }
      emails += "]";
      std::string body = "{\"environmentId\":\"1\",\"version\":7,\"flags\":[{\"key\":" +
                         json_quote(*f.get("key")->as_string()) + ",\"enabled\":" +
                         (*f.get("enabled")->as_bool() ? "true" : "false") + ",\"rolloutBps\":" +
                         std::to_string(*f.get("rolloutBps")->as_integer()) +
                         ",\"overrideEmails\":" + emails + "}]}";
      ruleset = parse_ruleset(body);
      REQUIRE(ruleset);
      rule = &ruleset->flags.begin()->second;
    }
    auto got = evaluate(rule, user_from(*item.get("user")), 7, *item.get("default")->as_bool());
    const auto& expect = *item.get("expect");
    CHECK(got.value == *expect.get("value")->as_bool());
    CHECK(std::string(reason_name(got.reason)) == *expect.get("reason")->as_string());
    CHECK(got.version == 7);
  }
}

TEST_CASE("an invalid rule rejects the whole ruleset") {
  const char* invalid[] = {
      R"({"key":"a","enabled":true,"rolloutBps":10001,"overrideEmails":[]})",
      R"({"key":"a","enabled":true,"rolloutBps":50.5,"overrideEmails":[]})",
      R"({"key":"a","enabled":true,"rolloutBps":true,"overrideEmails":[]})",
      R"({"key":"a","enabled":1,"rolloutBps":0,"overrideEmails":[]})",
      R"({"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":[1]})",
      R"({"key":"a","enabled":true,"rolloutBps":0})",
  };
  for (const char* flag : invalid) {
    CAPTURE(flag);
    std::string body = std::string(R"({"environmentId":"1","version":1,"flags":[{"key":"b","enabled":true,"rolloutBps":0,"overrideEmails":[]},)") +
                       flag + "]}";
    CHECK_FALSE(parse_ruleset(body));
  }
  CHECK_FALSE(parse_ruleset(R"({"environmentId":"1","version":1,"flags":[]} trailing)"));
  CHECK(parse_ruleset(R"({"environmentId":"1","version":1.0,"flags":[]})"));
  CHECK(parse_ruleset(R"({"environmentId":"1","version":1e2,"flags":[]})")->version == 100);
}

TEST_CASE("JSON strings decode escapes and surrogate pairs") {
  auto parsed = parse_json(R"(["a\"b\\cü😀\ud800x"])");
  REQUIRE(parsed);
  CHECK(*(*parsed->as_array())[0].as_string() == "a\"b\\c\xc3\xbc\xf0\x9f\x98\x80\xef\xbf\xbdx");
  CHECK_FALSE(parse_json("[01]"));
  CHECK_FALSE(parse_json("{\"a\":}"));
  CHECK_FALSE(parse_json("\"\x01\""));
}

TEST_CASE("JSON output replaces invalid UTF-8") {
  CHECK(json_quote("\xff" "a\n") == "\"\\ufffda\\n\"");
  CHECK(json_quote("\xc3\xbc") == "\"\xc3\xbc\"");
}

TEST_CASE("lengths count UTF-16 units and trimming matches JavaScript") {
  CHECK(js_length("abc") == 3);
  CHECK(js_length("\xc3\xbc") == 1);
  CHECK(js_length("\xf0\x9f\x98\x80") == 2);
  CHECK(js_trim("\xc2\xa0 a b\xe3\x80\x80\t") == "a b");
  CHECK(js_trim("\xef\xbb\xbf") == "");
}
