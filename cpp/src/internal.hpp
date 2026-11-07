// Internal pieces of the SDK. Not part of the public API.
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

#include "flagbearer/flagbearer.hpp"

typedef void CURLM;
typedef void CURL;

namespace flagbearer {
namespace detail {

using Clock = std::chrono::steady_clock;
using Ms = std::chrono::milliseconds;

// Every delay the client uses. Tests shorten them.
struct Timings {
  Ms request_timeout{5000};
  Ms poll_interval{30000};
  Ms retry_base{1000};
  Ms max_retry{30000};
  Ms max_version_refresh_delay{2000};
  Ms stream_connect_timeout{5000};
  Ms stream_idle_timeout{45000};
  Ms exposure_flush_interval{30000};
  Ms exposure_timeout{10000};
};

// ---- JSON -------------------------------------------------------------------

struct Json;
using JsonArray = std::vector<Json>;
using JsonObject = std::vector<std::pair<std::string, Json>>;

// A number keeps its source text so integers aren't rounded through a double.
struct JsonNumber {
  std::string text;
};

struct Json {
  std::variant<std::nullptr_t, bool, JsonNumber, std::string, JsonArray, JsonObject> value;

  const Json* get(std::string_view key) const;  // nullptr unless an object with key
  const std::string* as_string() const;
  const bool* as_bool() const;
  const JsonArray* as_array() const;
  const JsonObject* as_object() const;
  // An integral number, as JavaScript reads it: 42 and 42.0 both qualify.
  std::optional<int64_t> as_integer() const;
};

// Strict RFC 8259 parse of the whole text; nothing but whitespace may follow.
std::optional<Json> parse_json(std::string_view text);
std::string json_quote(std::string_view text);

// ---- Evaluation ---------------------------------------------------------------

inline constexpr int64_t kMaxSafeInteger = (int64_t{1} << 53) - 1;

std::array<uint8_t, 32> sha256(std::string_view data);
int64_t bucket(std::string_view flag_key, std::string_view user_key);

struct FlagRule {
  std::string key;
  bool enabled = false;
  int64_t rollout_bps = 0;
  std::vector<std::string> override_emails;
};

struct Ruleset {
  std::string environment_id;
  int64_t version = 0;
  std::unordered_map<std::string, FlagRule> flags;
};

enum class Reason { Off, Tester, Rollout, Fallback };
const char* reason_name(Reason reason);

struct Evaluation {
  bool value;
  Reason reason;
  std::optional<int64_t> version;
};

Evaluation evaluate(const FlagRule* flag, const User& user, std::optional<int64_t> version,
                    bool fallback);
std::optional<int64_t> parse_version(const Json* value);
std::optional<Ruleset> parse_ruleset(std::string_view body);

// JavaScript's String.prototype.trim and an ASCII-only toLowerCase.
std::string js_trim(std::string_view text);
std::string ascii_lower(std::string_view text);
// Decodes one UTF-8 code point at pos and advances past it. Invalid bytes
// advance by one, yield U+FFFD and return false.
bool next_code_point(std::string_view text, size_t& pos, uint32_t& code);
// UTF-16 code units, as JavaScript and the server's limits count them.
size_t js_length(std::string_view utf8);

// ---- Logging ------------------------------------------------------------------

struct Field {
  const char* name;
  std::string value;
};
std::string optional_field(const std::optional<int64_t>& value);

class DebugLog {
 public:
  explicit DebugLog(bool enabled) : enabled_(enabled) {}
  void operator()(const char* message, std::initializer_list<Field> fields = {}) const;

 private:
  bool enabled_;
};
void log_error(const std::string& message);

// ---- HTTP ---------------------------------------------------------------------

// A failed request, classified for retries.
struct RequestError {
  std::string message;
  bool retryable = false;
  Ms retry_after{0};
};

class RefreshFailure : public Error {
 public:
  explicit RefreshFailure(const RequestError& error)
      : Error(error.message, !error.retryable), retry_after(error.retry_after) {}
  Ms retry_after;
};

// Wakes and stops every transfer registered with it, from any thread.
class Cancellation {
 public:
  void cancel();
  bool cancelled() const { return cancelled_.load(); }
  void add(CURLM* multi);
  void remove(CURLM* multi);

 private:
  std::atomic<bool> cancelled_{false};
  std::mutex mutex_;
  std::vector<CURLM*> active_;
};

struct Response {
  long status = 0;
  std::string content_type;
  std::string retry_after;
  std::string body;
};

struct Request {
  std::string method = "GET";
  std::string url;
  std::vector<std::string> headers;
  std::string body;
  Ms timeout{5000};
};

void global_curl_init();
// Allows only HTTP and HTTPS on a transfer.
void restrict_protocols(CURL* easy);
// Sends one request and reads the whole response. Network failures, timeouts and
// cancellation become a retryable RequestError.
std::variant<Response, RequestError> send(const Request& request, Cancellation& cancel);
RequestError status_error(const Response& response);
Ms parse_retry_after(const std::string& header);
Ms backoff(int attempt, Ms base, Ms cap);
Ms jitter(Ms interval, double spread);
double random_fraction();
std::string new_uuid();

// ---- Refresh ------------------------------------------------------------------

class Coordinator {
 public:
  // fetch returns nullptr when the ruleset is unchanged (304).
  using Fetch = std::function<std::shared_ptr<const Ruleset>()>;
  using Apply = std::function<void(std::shared_ptr<const Ruleset>)>;

  Coordinator(Fetch fetch, Apply apply, std::function<void()> on_permanent, Timings timings,
              DebugLog debug);
  ~Coordinator();

  void start();
  void sync(Ms timeout);
  void refresh_in_background();
  void on_version(int64_t version);
  void set_streaming(bool connected, bool refresh);
  void close();
  void join();

 private:
  struct Waiter {
    std::promise<void> promise;
  };

  bool behind() const;
  void background_locked();
  void ensure_polling();
  void clear_polling();
  void schedule_retry(const RefreshFailure* failure);
  std::optional<Clock::duration> next_due(Clock::time_point now) const;
  bool take_due(Clock::time_point now);
  void run();
  void refresh();

  Fetch fetch_;
  Apply apply_;
  std::function<void()> on_permanent_;
  Timings t_;
  DebugLog debug_;
  std::thread thread_;

  mutable std::mutex mutex_;
  std::condition_variable wake_;
  bool stopped_ = false;
  bool blocked_ = false;
  bool streaming_ = false;
  std::optional<int64_t> version_;
  std::optional<int64_t> wanted_;
  int attempt_ = 0;
  Clock::time_point not_before_{};
  std::optional<Clock::time_point> retry_at_;
  std::optional<Clock::time_point> poll_at_;
  std::optional<Clock::time_point> version_at_;
  bool requested_ = false;
  bool in_flight_ = false;
  std::vector<std::shared_ptr<Waiter>> waiters_;
};

// ---- Stream -------------------------------------------------------------------

inline constexpr size_t kMaxEventLength = 65536;

// Server-Sent Events fed in arbitrary pieces. Calls on_version for each
// well-formed version event and ignores everything else. feed returns false
// when a line or event grows past the limit.
class EventParser {
 public:
  explicit EventParser(std::function<void(int64_t)> on_version)
      : on_version_(std::move(on_version)) {}
  bool feed(std::string_view chunk);

 private:
  bool line(std::string_view line);

  std::function<void(int64_t)> on_version_;
  std::string buffer_;
  std::string event_ = "message";
  std::string data_;
};

struct StreamOptions {
  std::string url;
  std::vector<std::string> headers;
  Timings timings;
  DebugLog debug{false};
  std::function<void(int64_t)> on_version;
  std::function<void()> on_open;
  std::function<void()> on_disconnect;
};

class VersionStream {
 public:
  explicit VersionStream(StreamOptions options);
  ~VersionStream();
  void start();
  void close();
  void join();

 private:
  void run();
  // One connection; returns its failure, if any.
  std::optional<RequestError> connect();

  StreamOptions o_;
  Cancellation cancel_;
  std::thread thread_;
  std::mutex mutex_;
  std::condition_variable wake_;
  std::atomic<bool> closed_{false};
  int attempts_ = 0;
};

// ---- Exposures ----------------------------------------------------------------

class ExposureReporter {
 public:
  ExposureReporter(std::string url, std::string key, std::string app_version, Timings timings);
  ~ExposureReporter();
  void start();
  void record(std::string_view flag, const User& user, const Evaluation& details) noexcept;
  void flush();  // throws Error
  void close();  // flushes once; throws Error if that fails

 private:
  struct Who {
    std::string key;
    std::string email;
    bool operator==(const Who& other) const { return key == other.key && email == other.email; }
  };
  struct Event {
    std::string flag;
    bool result;
    Reason reason;
    std::optional<int64_t> version;
    std::string bucket;
    int64_t check_count = 1;
    std::string first_checked_at;
    std::string last_checked_at;
  };
  struct Batch {
    std::string id;
    Who user;
    std::vector<std::shared_ptr<Event>> events;
  };

  void run();
  void send();
  void seal();
  void settle(const std::shared_ptr<Batch>& batch);
  std::variant<Response, RequestError> send_request(const Request& request);

  std::string url_;
  std::string key_;
  std::string app_version_;
  Timings t_;
  std::string reporter_id_;
  Cancellation cancel_;  // never cancelled: the last flush must still run

  std::mutex send_mutex_;
  std::mutex mutex_;
  std::condition_variable wake_;
  std::map<std::string, std::pair<Who, std::shared_ptr<Event>>> active_;
  std::vector<std::string> order_;
  std::vector<std::shared_ptr<Batch>> sealed_;
  size_t sealed_events_ = 0;
  int64_t dropped_ = 0;
  int64_t reported_dropped_ = 0;
  Clock::time_point retry_at_{};
  int failures_ = 0;
  bool closed_ = false;
  std::thread thread_;
};

std::string iso_millis(std::chrono::system_clock::time_point at);

// ---- Client -------------------------------------------------------------------

class ClientImpl {
 public:
  ClientImpl(const std::string& key, const Options& options, const Timings& timings);
  ~ClientImpl();
  void sync(Ms timeout);
  bool is_enabled(std::string_view flag, const User& user, bool fallback) noexcept;
  void flush();
  void close() noexcept;

 private:
  std::shared_ptr<const Ruleset> fetch();

  std::string ruleset_url_;
  std::vector<std::string> headers_;
  Timings t_;
  Cancellation fetch_cancel_;
  mutable std::mutex ruleset_mutex_;
  std::shared_ptr<const Ruleset> ruleset_;
  std::unique_ptr<ExposureReporter> reporter_;
  std::unique_ptr<Coordinator> coordinator_;
  std::unique_ptr<VersionStream> stream_;
  std::atomic<bool> connected_{false};
  std::once_flag close_once_;
};

}  // namespace detail
}  // namespace flagbearer
