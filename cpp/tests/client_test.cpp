#include <doctest/doctest.h>

#include <algorithm>
#include <map>
#include <set>

#include "fake_sdk.hpp"
#include "internal.hpp"

using flagbearer::Client;
using flagbearer::Error;
using flagbearer::User;
using namespace flagbearer::detail;
using namespace std::chrono_literals;

namespace {

// Short enough that retries finish within a test, long enough that nothing
// polls unless a test asks for it.
Timings fast() {
  Timings t;
  t.request_timeout = 1000ms;
  t.poll_interval = 3600000ms;
  t.retry_base = 10ms;
  t.max_retry = 50ms;
  t.max_version_refresh_delay = 0ms;
  t.stream_connect_timeout = 1000ms;
  t.stream_idle_timeout = 5000ms;
  t.exposure_flush_interval = 3600000ms;
  t.exposure_timeout = 1000ms;
  return t;
}

std::unique_ptr<Client> client_for(FakeSdk& fake, Timings timings = fast(), std::string app_version = "") {
  flagbearer::Options options;
  options.url = fake.url();
  options.app_version = std::move(app_version);
  return Client::create_for_tests("fbs_test", options, timings);
}

const User user1{std::string("user-1"), std::nullopt};

std::vector<std::string> uploads(FakeSdk& fake) {
  return fake.snapshot<std::vector<std::string>>([](const FakeSdk& f) { return f.exposures; });
}

Json last_upload(FakeSdk& fake) {
  auto all = uploads(fake);
  REQUIRE_FALSE(all.empty());
  auto parsed = parse_json(all.back());
  REQUIRE(parsed);
  return *parsed;
}

int stream_connections(FakeSdk& fake) {
  return fake.snapshot<int>([](const FakeSdk& f) { return f.stream_connections; });
}

}  // namespace

TEST_CASE("checks return the default before any ruleset") {
  FakeSdk fake;
  fake.set([](FakeSdk& f) { f.ruleset_statuses.assign(1000, 503); });
  auto client = client_for(fake);
  CHECK(client->is_enabled("checkout", user1, true));
  CHECK_FALSE(client->is_enabled("checkout", user1, false));
}

TEST_CASE("checks use the rules after a sync") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  CHECK(client->is_enabled("checkout", user1, false));
  CHECK(client->is_enabled("missing", user1, true));
}

TEST_CASE("a stream announcement refetches with the current version") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  REQUIRE(fake.wait_for_stream(3000));
  fake.set([](FakeSdk& f) {
    f.flags = {{"checkout", false, 10000}};
    f.version = 2;
  });
  fake.announce(2);
  CHECK(wait_until([&] { return !client->is_enabled("checkout", user1, true); }));
  auto requests = fake.snapshot<std::vector<std::string>>([](const FakeSdk& f) { return f.ruleset_requests; });
  CHECK(std::find(requests.begin(), requests.end(), "\"1\"") != requests.end());
}

TEST_CASE("an unchanged ruleset keeps the rules") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  client->sync(3s);
  auto last = fake.snapshot<std::string>([](const FakeSdk& f) { return f.ruleset_requests.back(); });
  CHECK(last == "\"1\"");
  CHECK(client->is_enabled("checkout", user1, false));
}

TEST_CASE("an invalid ruleset fails permanently and keeps the last rules") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  fake.set([](FakeSdk& f) {
    f.version = 2;
    f.ruleset_body = R"({"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]})";
  });
  bool permanent = false;
  try {
    client->sync(3s);
  } catch (const Error& error) {
    permanent = error.is_permanent();
  }
  CHECK(permanent);
  CHECK(client->is_enabled("checkout", user1, false));

  // An explicit sync tries again after a permanent failure.
  fake.set([](FakeSdk& f) {
    f.ruleset_body.clear();
    f.flags = {{"checkout", false, 10000}};
  });
  client->sync(3s);
  CHECK_FALSE(client->is_enabled("checkout", user1, true));
}

TEST_CASE("sync after a permanent failure restarts polling") {
  FakeSdk fake;
  fake.set([](FakeSdk& f) { f.stream_status = 401; });
  auto timings = fast();
  timings.poll_interval = 50ms;
  auto client = client_for(fake, timings);
  client->sync(3s);
  fake.set([](FakeSdk& f) {
    f.ruleset_body = "not json";
    f.version = 2;
  });
  CHECK_THROWS_AS(client->sync(3s), Error);
  fake.set([](FakeSdk& f) { f.ruleset_body.clear(); });
  client->sync(3s);
  fake.set([](FakeSdk& f) {
    f.flags = {{"checkout", false, 10000}};
    f.version = 3;
  });
  CHECK(wait_until([&] { return !client->is_enabled("checkout", user1, true); }));
}

TEST_CASE("a ruleset for another environment is rejected") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  fake.set([](FakeSdk& f) {
    f.environment_id = "env-2";
    f.flags = {{"checkout", false, 10000}};
    f.version = 2;
  });
  std::string message;
  try {
    client->sync(3s);
  } catch (const Error& error) {
    message = error.what();
  }
  CHECK(message.find("environment changed") != std::string::npos);
  CHECK(client->is_enabled("checkout", user1, false));
}

TEST_CASE("a redirect is a permanent failure") {
  FakeSdk fake;
  fake.set([](FakeSdk& f) { f.redirect_ruleset = true; });
  auto client = client_for(fake);
  bool permanent = false;
  try {
    client->sync(3s);
  } catch (const Error& error) {
    permanent = error.is_permanent();
  }
  CHECK(permanent);
  CHECK_FALSE(client->is_enabled("checkout", user1, false));
}

TEST_CASE("server errors retry in the background") {
  FakeSdk fake;
  // Without a stream, only the retry schedule can recover.
  fake.set([](FakeSdk& f) {
    f.stream_status = 401;
    f.ruleset_statuses = {503, 500, 429};
  });
  auto client = client_for(fake);
  bool retryable = false;
  try {
    client->sync(3s);
  } catch (const Error& error) {
    retryable = !error.is_permanent();
  }
  CHECK(retryable);
  CHECK(wait_until([&] { return client->is_enabled("checkout", user1, false); }));
}

TEST_CASE("sync honors its timeout") {
  FakeSdk fake;
  fake.set([](FakeSdk& f) { f.ruleset_statuses.assign(5, 503); });
  auto timings = fast();
  timings.retry_base = 3600000ms;
  timings.max_retry = 3600000ms;
  auto client = client_for(fake, timings);
  try {
    client->sync(3s);
  } catch (const Error&) {
  }
  auto started = std::chrono::steady_clock::now();
  CHECK_THROWS_AS(client->sync(50ms), Error);
  CHECK(std::chrono::steady_clock::now() - started < 1s);
}

TEST_CASE("the stream outlives its connect timeout, and close ends it promptly") {
  FakeSdk fake;
  auto timings = fast();
  timings.stream_connect_timeout = 50ms;
  auto client = client_for(fake, timings);
  client->sync(3s);
  REQUIRE(fake.wait_for_stream(3000));
  std::this_thread::sleep_for(300ms);
  CHECK(stream_connections(fake) == 1);

  // The idle timeout is 5 seconds, so only an abort ends the read this soon.
  auto started = std::chrono::steady_clock::now();
  client->close();
  CHECK(std::chrono::steady_clock::now() - started < 1s);
}

TEST_CASE("a silent stream is dropped and reconnected") {
  FakeSdk fake;
  auto timings = fast();
  timings.stream_idle_timeout = 100ms;
  auto client = client_for(fake, timings);
  client->sync(3s);
  // The fake sends nothing after its opening event, like a dead connection.
  CHECK(wait_until([&] { return stream_connections(fake) >= 2; }));
}

TEST_CASE("heartbeats keep the stream open past the idle timeout") {
  FakeSdk fake;
  auto timings = fast();
  timings.stream_idle_timeout = 300ms;
  auto client = client_for(fake, timings);
  client->sync(3s);
  REQUIRE(fake.wait_for_stream(3000));
  for (int i = 0; i < 9; ++i) {
    fake.ping();
    std::this_thread::sleep_for(100ms);
  }
  CHECK(stream_connections(fake) == 1);
}

TEST_CASE("a rejected stream falls back to polling") {
  FakeSdk fake;
  fake.set([](FakeSdk& f) { f.stream_status = 401; });
  auto timings = fast();
  timings.poll_interval = 50ms;
  auto client = client_for(fake, timings);
  client->sync(3s);
  fake.set([](FakeSdk& f) {
    f.flags = {{"checkout", false, 10000}};
    f.version = 2;
  });
  CHECK(wait_until([&] { return !client->is_enabled("checkout", user1, true); }));
}

TEST_CASE("exposures count repeated checks and batch by user") {
  FakeSdk fake;
  auto client = client_for(fake, fast(), "build-7");
  client->sync(3s);
  for (int i = 0; i < 3; ++i) {
    client->is_enabled("checkout", User{std::string("user-1"), std::string(" Ann@Example.com ")}, false);
  }
  client->is_enabled("checkout", User{std::string("user-2"), std::nullopt}, false);
  client->flush();

  std::map<std::string, Json> by_user;
  std::set<std::string> reporters;
  for (const auto& body : uploads(fake)) {
    auto upload = *parse_json(body);
    by_user[*upload.get("user")->get("key")->as_string()] = upload;
    reporters.insert(*upload.get("reporterId")->as_string());
    CHECK(*upload.get("sdk")->as_string() == "cpp");
    CHECK(*upload.get("sdkVersion")->as_string() == flagbearer::kVersion);
    CHECK(*upload.get("appVersion")->as_string() == "build-7");
  }
  CHECK(reporters.size() == 1);
  const auto& first = by_user["user-1"];
  CHECK(*first.get("user")->get("email")->as_string() == "ann@example.com");
  const auto& event = (*first.get("events")->as_array())[0];
  CHECK(*event.get("checkCount")->as_integer() == 3);
  CHECK(*event.get("result")->as_bool());
  CHECK(*event.get("reason")->as_string() == "rollout");
  CHECK(*event.get("configurationVersion")->as_integer() == 1);
  const auto& bucket_at = *event.get("bucket")->as_string();
  CHECK(bucket_at.size() == 24);
  CHECK(bucket_at.substr(19) == ".000Z");
  int seconds = std::stoi(bucket_at.substr(17, 2));
  CHECK(seconds % 30 == 0);
  CHECK(*event.get("firstCheckedAt")->as_string() >= bucket_at);
  CHECK(*(*by_user["user-2"].get("events")->as_array())[0].get("checkCount")->as_integer() == 1);
}

TEST_CASE("a failed upload retries the same batch") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  client->is_enabled("checkout", user1, false);
  fake.set([](FakeSdk& f) { f.exposure_statuses = {500}; });
  CHECK_THROWS_AS(client->flush(), Error);
  client->flush();
  auto all = uploads(fake);
  REQUIRE(all.size() == 2);
  CHECK(*parse_json(all[0])->get("batchId")->as_string() == *parse_json(all[1])->get("batchId")->as_string());
}

TEST_CASE("a refused upload is dropped and counted as lost") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  client->is_enabled("checkout", user1, false);
  client->is_enabled("checkout", user1, false);
  fake.set([](FakeSdk& f) { f.exposure_statuses = {400}; });
  CHECK_THROWS_AS(client->flush(), Error);
  client->flush();
  auto last = last_upload(fake);
  CHECK(last.get("events")->as_array()->empty());
  CHECK(*last.get("droppedChecks")->as_integer() == 2);
}

TEST_CASE("checks that can't be recorded are counted as lost") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  CHECK(client->is_enabled(std::string(257, 'x'), user1, true));
  // 200 emoji are 400 UTF-16 units: past the server's 256 limit.
  std::string emoji;
  for (int i = 0; i < 200; ++i) emoji += "\xf0\x9f\x98\x80";
  client->is_enabled("checkout", User{emoji, std::nullopt}, true);
  client->flush();
  auto last = last_upload(fake);
  CHECK(*last.get("droppedChecks")->as_integer() == 2);
  CHECK(last.get("events")->as_array()->empty());
}

TEST_CASE("close flushes once and keeps rules usable") {
  FakeSdk fake;
  auto client = client_for(fake);
  client->sync(3s);
  client->is_enabled("checkout", user1, false);
  client->close();
  client->close();
  CHECK(uploads(fake).size() == 1);
  CHECK(client->is_enabled("checkout", user1, false));
  CHECK_THROWS_AS(client->sync(1s), Error);
}

TEST_CASE("create rejects bad keys and urls") {
  flagbearer::Options good;
  CHECK_THROWS_AS(Client::create("", good), std::invalid_argument);
  CHECK_THROWS_AS(Client::create("fbs_ bad", good), std::invalid_argument);
  flagbearer::Options ftp;
  ftp.url = "ftp://example.com";
  CHECK_THROWS_AS(Client::create("fbs_test", ftp), std::invalid_argument);
  flagbearer::Options hostless;
  hostless.url = "http://";
  CHECK_THROWS_AS(Client::create("fbs_test", hostless), std::invalid_argument);
}
