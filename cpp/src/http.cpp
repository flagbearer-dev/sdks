#include <curl/curl.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <random>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

namespace {

constexpr size_t kMaxBody = 64 * 1024 * 1024;

std::mt19937_64& rng() {
  thread_local std::mt19937_64 engine{std::random_device{}()};
  return engine;
}

struct Collected {
  std::string body;
  std::string retry_after;
  bool too_large = false;
};

size_t on_body(char* data, size_t size, size_t count, void* user) {
  auto* collected = static_cast<Collected*>(user);
  size_t bytes = size * count;
  if (collected->body.size() + bytes > kMaxBody) {
    collected->too_large = true;
    return 0;
  }
  collected->body.append(data, bytes);
  return bytes;
}

size_t on_header(char* data, size_t size, size_t count, void* user) {
  auto* collected = static_cast<Collected*>(user);
  std::string_view line(data, size * count);
  constexpr std::string_view kName = "retry-after:";
  if (line.size() > kName.size()) {
    std::string name = ascii_lower(line.substr(0, kName.size()));
    if (name == kName) {
      std::string value(line.substr(kName.size()));
      value.erase(0, value.find_first_not_of(" \t"));
      value.erase(value.find_last_not_of(" \t\r\n") + 1);
      collected->retry_after = value;
    }
  }
  return size * count;
}

}  // namespace

void restrict_protocols(CURL* easy) {
#if LIBCURL_VERSION_NUM >= 0x075500
  curl_easy_setopt(easy, CURLOPT_PROTOCOLS_STR, "http,https");
#else
  curl_easy_setopt(easy, CURLOPT_PROTOCOLS, static_cast<long>(CURLPROTO_HTTP | CURLPROTO_HTTPS));
#endif
}

void global_curl_init() {
  static std::once_flag once;
  std::call_once(once, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
}

void Cancellation::cancel() {
  std::lock_guard<std::mutex> lock(mutex_);
  cancelled_ = true;
  for (CURLM* multi : active_) curl_multi_wakeup(multi);
}

void Cancellation::add(CURLM* multi) {
  std::lock_guard<std::mutex> lock(mutex_);
  active_.push_back(multi);
}

void Cancellation::remove(CURLM* multi) {
  std::lock_guard<std::mutex> lock(mutex_);
  active_.erase(std::remove(active_.begin(), active_.end(), multi), active_.end());
}

std::variant<Response, RequestError> send(const Request& request, Cancellation& cancel) {
  CURL* easy = curl_easy_init();
  CURLM* multi = curl_multi_init();
  if (!easy || !multi) {
    if (easy) curl_easy_cleanup(easy);
    if (multi) curl_multi_cleanup(multi);
    return RequestError{"Flagbearer request failed: libcurl unavailable", true, Ms{0}};
  }
  Collected collected;
  curl_slist* headers = nullptr;
  for (const auto& header : request.headers) headers = curl_slist_append(headers, header.c_str());
  headers = curl_slist_append(headers, "Expect:");

  curl_easy_setopt(easy, CURLOPT_URL, request.url.c_str());
  curl_easy_setopt(easy, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(easy, CURLOPT_NOSIGNAL, 1L);
  curl_easy_setopt(easy, CURLOPT_FOLLOWLOCATION, 0L);
  restrict_protocols(easy);
  curl_easy_setopt(easy, CURLOPT_TIMEOUT_MS, static_cast<long>(request.timeout.count()));
  curl_easy_setopt(easy, CURLOPT_ACCEPT_ENCODING, "");
  curl_easy_setopt(easy, CURLOPT_WRITEFUNCTION, on_body);
  curl_easy_setopt(easy, CURLOPT_WRITEDATA, &collected);
  curl_easy_setopt(easy, CURLOPT_HEADERFUNCTION, on_header);
  curl_easy_setopt(easy, CURLOPT_HEADERDATA, &collected);
  if (request.method == "POST") {
    curl_easy_setopt(easy, CURLOPT_POST, 1L);
    curl_easy_setopt(easy, CURLOPT_POSTFIELDS, request.body.c_str());
    curl_easy_setopt(easy, CURLOPT_POSTFIELDSIZE_LARGE, static_cast<curl_off_t>(request.body.size()));
  }

  curl_multi_add_handle(multi, easy);
  cancel.add(multi);
  CURLcode result = CURLE_OK;
  bool aborted = false;
  while (true) {
    if (cancel.cancelled()) {
      aborted = true;
      break;
    }
    int running = 0;
    curl_multi_perform(multi, &running);
    if (running == 0) {
      int queued = 0;
      while (CURLMsg* message = curl_multi_info_read(multi, &queued)) {
        if (message->msg == CURLMSG_DONE) result = message->data.result;
      }
      break;
    }
    curl_multi_poll(multi, nullptr, 0, 1000, nullptr);
  }
  cancel.remove(multi);

  Response response;
  long status = 0;
  curl_easy_getinfo(easy, CURLINFO_RESPONSE_CODE, &status);
  const char* content_type = nullptr;
  curl_easy_getinfo(easy, CURLINFO_CONTENT_TYPE, &content_type);
  response.status = status;
  if (content_type) response.content_type = content_type;
  response.retry_after = collected.retry_after;
  response.body = std::move(collected.body);
  std::string failure = aborted ? "request cancelled" : curl_easy_strerror(result);

  curl_multi_remove_handle(multi, easy);
  curl_easy_cleanup(easy);
  curl_multi_cleanup(multi);
  curl_slist_free_all(headers);

  if (aborted || result != CURLE_OK) {
    if (collected.too_large) failure = "response too large";
    return RequestError{"Flagbearer request failed: " + failure, true, Ms{0}};
  }
  return response;
}

Ms parse_retry_after(const std::string& header) {
  if (header.empty()) return Ms{0};
  char* end = nullptr;
  double seconds = std::strtod(header.c_str(), &end);
  if (end && *end == '\0' && end != header.c_str()) {
    if (!std::isfinite(seconds) || seconds < 0) return Ms{0};
    return Ms{static_cast<int64_t>(std::min(seconds, 1e9) * 1000)};
  }
  time_t at = curl_getdate(header.c_str(), nullptr);
  if (at == -1) return Ms{0};
  auto delta = std::chrono::system_clock::from_time_t(at) - std::chrono::system_clock::now();
  return std::max(Ms{0}, std::chrono::duration_cast<Ms>(delta));
}

RequestError status_error(const Response& response) {
  long status = response.status;
  // A 3xx lands here too: requests never follow redirects.
  return RequestError{"Flagbearer request failed: " + std::to_string(status),
                      status == 408 || status == 429 || status >= 500,
                      parse_retry_after(response.retry_after)};
}

double random_fraction() { return std::uniform_real_distribution<double>(0.0, 1.0)(rng()); }

Ms backoff(int attempt, Ms base, Ms cap) {
  Ms delay = std::min(cap, base * (int64_t{1} << std::min(attempt, 5)));
  auto jittered = static_cast<int64_t>(static_cast<double>(delay.count()) * (0.8 + random_fraction() * 0.4));
  return std::min(cap, Ms{jittered});
}

Ms jitter(Ms interval, double spread) {
  return Ms{static_cast<int64_t>(static_cast<double>(interval.count()) *
                                 (1 - spread + random_fraction() * 2 * spread))};
}

std::string new_uuid() {
  std::array<uint8_t, 16> b{};
  for (auto& byte : b) byte = static_cast<uint8_t>(rng()() & 0xFF);
  b[6] = static_cast<uint8_t>((b[6] & 0x0F) | 0x40);
  b[8] = static_cast<uint8_t>((b[8] & 0x3F) | 0x80);
  char out[37];
  std::snprintf(out, sizeof out,
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x", b[0], b[1],
                b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
  return out;
}

// ---- Logging ------------------------------------------------------------------

namespace {
std::mutex& log_mutex() {
  static std::mutex mutex;
  return mutex;
}
}  // namespace

std::string optional_field(const std::optional<int64_t>& value) {
  return value ? std::to_string(*value) : "undefined";
}

void DebugLog::operator()(const char* message, std::initializer_list<Field> fields) const {
  if (!enabled_) return;
  try {
    std::string line = std::string("[Flagbearer] ") + message;
    if (fields.size() > 0) {
      // Same shape as the Node SDK's console.debug output: { version: 42 }.
      line += " {";
      bool first = true;
      for (const auto& field : fields) {
        line += first ? " " : ", ";
        line += field.name;
        line += ": ";
        line += field.value;
        first = false;
      }
      line += " }";
    }
    std::lock_guard<std::mutex> lock(log_mutex());
    std::fprintf(stderr, "%s\n", line.c_str());
  } catch (...) {
    // Diagnostics must never interrupt flag updates.
  }
}

void log_error(const std::string& message) {
  try {
    std::lock_guard<std::mutex> lock(log_mutex());
    std::fprintf(stderr, "%s\n", message.c_str());
  } catch (...) {
  }
}

}  // namespace detail
}  // namespace flagbearer
