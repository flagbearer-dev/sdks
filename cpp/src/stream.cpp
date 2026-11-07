#include <curl/curl.h>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

// Lines are split on CR and LF bytes, which never occur inside a multi-byte
// UTF-8 character, so a character split across reads needs no special care.
bool EventParser::feed(std::string_view chunk) {
  buffer_.append(chunk);
  size_t start = 0;
  for (size_t index = 0; index < buffer_.size(); ++index) {
    char c = buffer_[index];
    if (c != '\r' && c != '\n') continue;
    // A CR at the end might be the first half of a CRLF in the next piece.
    if (c == '\r' && index == buffer_.size() - 1) break;
    if (!line(std::string_view(buffer_).substr(start, index - start))) return false;
    if (c == '\r' && buffer_[index + 1] == '\n') ++index;
    start = index + 1;
  }
  buffer_.erase(0, start);
  return buffer_.size() <= kMaxEventLength;
}

bool EventParser::line(std::string_view line) {
  if (line.empty()) {
    if (event_ == "version" && !data_.empty()) {
      auto parsed = parse_json(std::string_view(data_).substr(0, data_.size() - 1));
      if (parsed) {
        if (auto version = parse_version(parsed->get("version"))) on_version_(*version);
      }
    }
    event_ = "message";
    data_.clear();
    return true;
  }
  if (line.front() == ':') return true;
  size_t colon = line.find(':');
  std::string_view name = line.substr(0, colon);
  std::string_view content = colon == std::string_view::npos ? std::string_view() : line.substr(colon + 1);
  if (!content.empty() && content.front() == ' ') content.remove_prefix(1);
  if (name == "event") {
    event_ = std::string(content);
  } else if (name == "data") {
    data_.append(content).append("\n");
    if (data_.size() > kMaxEventLength) return false;
  }
  return true;
}

VersionStream::VersionStream(StreamOptions options) : o_(std::move(options)) {}

VersionStream::~VersionStream() {
  close();
  join();
}

void VersionStream::join() {
  if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
}

void VersionStream::start() { thread_ = std::thread([this] { run(); }); }

void VersionStream::close() {
  if (closed_.exchange(true)) return;
  o_.debug("Stream closed");
  cancel_.cancel();
  wake_.notify_all();
}

void VersionStream::run() {
  while (!closed_) {
    auto failure = connect();
    if (closed_) return;
    o_.on_disconnect();
    if (failure && !failure->retryable) {
      // Polling remains available; the next ruleset request also checks the key.
      log_error("Flagbearer stream stopped; using polling: " + failure->message);
      return;
    }
    Ms delay = backoff(attempts_, o_.timings.retry_base, o_.timings.max_retry);
    ++attempts_;
    if (failure) delay = std::max(delay, failure->retry_after);
    o_.debug("Stream reconnect scheduled", {{"delayMs", std::to_string(delay.count())}});
    std::unique_lock<std::mutex> lock(mutex_);
    wake_.wait_for(lock, delay, [this] { return closed_.load(); });
  }
}

namespace {

struct Connection {
  VersionStream* stream;
  const StreamOptions* options;
  EventParser parser;
  long status = 0;
  std::string content_type;
  std::string retry_after;
  bool opened = false;
  bool received = false;
  std::optional<RequestError> failure;
  Clock::time_point deadline;
};

size_t on_stream_header(char* data, size_t size, size_t count, void* user) {
  auto* c = static_cast<Connection*>(user);
  std::string_view line(data, size * count);
  if (line.substr(0, 5) == "HTTP/") {
    // A new response (after any interim one) starts with its status line.
    size_t space = line.find(' ');
    c->status = space == std::string_view::npos ? 0 : std::strtol(std::string(line.substr(space + 1, 3)).c_str(), nullptr, 10);
    c->content_type.clear();
    c->retry_after.clear();
    return size * count;
  }
  if (line == "\r\n" || line == "\n") {
    if (c->status >= 100 && c->status < 200) return size * count;
    Response response;
    response.status = c->status;
    response.retry_after = c->retry_after;
    if (c->status < 200 || c->status >= 300) {
      c->failure = status_error(response);
      return 0;
    }
    std::string type = ascii_lower(c->content_type.substr(0, c->content_type.find(';')));
    type.erase(type.find_last_not_of(" \t") + 1);
    if (type != "text/event-stream") {
      c->failure = RequestError{"Invalid Flagbearer stream response", false, Ms{0}};
      return 0;
    }
    // Heartbeats arrive every 15 seconds, so silence past the idle limit means a dead connection.
    c->opened = true;
    c->deadline = Clock::now() + c->options->timings.stream_idle_timeout;
    c->options->on_open();
    return size * count;
  }
  size_t colon = line.find(':');
  if (colon != std::string_view::npos) {
    std::string name = ascii_lower(line.substr(0, colon));
    std::string value(line.substr(colon + 1));
    value.erase(0, value.find_first_not_of(" \t"));
    value.erase(value.find_last_not_of(" \t\r\n") + 1);
    if (name == "content-type") c->content_type = value;
    if (name == "retry-after") c->retry_after = value;
  }
  return size * count;
}

size_t on_stream_body(char* data, size_t size, size_t count, void* user) {
  auto* c = static_cast<Connection*>(user);
  c->deadline = Clock::now() + c->options->timings.stream_idle_timeout;
  c->received = true;
  if (!c->parser.feed(std::string_view(data, size * count))) {
    c->failure = RequestError{"Flagbearer stream event too large", true, Ms{0}};
    return 0;
  }
  return size * count;
}

}  // namespace

std::optional<RequestError> VersionStream::connect() {
  o_.debug("Stream connecting");
  CURL* easy = curl_easy_init();
  CURLM* multi = curl_multi_init();
  if (!easy || !multi) {
    if (easy) curl_easy_cleanup(easy);
    if (multi) curl_multi_cleanup(multi);
    return RequestError{"Flagbearer stream failed: libcurl unavailable", true, Ms{0}};
  }
  Connection c{this, &o_, EventParser(o_.on_version), 0, {}, {}, false, false, std::nullopt,
               Clock::now() + o_.timings.stream_connect_timeout};
  curl_slist* headers = nullptr;
  for (const auto& header : o_.headers) headers = curl_slist_append(headers, header.c_str());
  headers = curl_slist_append(headers, "Accept: text/event-stream");

  curl_easy_setopt(easy, CURLOPT_URL, o_.url.c_str());
  curl_easy_setopt(easy, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(easy, CURLOPT_NOSIGNAL, 1L);
  curl_easy_setopt(easy, CURLOPT_FOLLOWLOCATION, 0L);
  restrict_protocols(easy);
  curl_easy_setopt(easy, CURLOPT_HEADERFUNCTION, on_stream_header);
  curl_easy_setopt(easy, CURLOPT_HEADERDATA, &c);
  curl_easy_setopt(easy, CURLOPT_WRITEFUNCTION, on_stream_body);
  curl_easy_setopt(easy, CURLOPT_WRITEDATA, &c);

  curl_multi_add_handle(multi, easy);
  cancel_.add(multi);
  CURLcode result = CURLE_OK;
  bool timed_out = false;
  while (!cancel_.cancelled()) {
    int running = 0;
    curl_multi_perform(multi, &running);
    if (running == 0) {
      int queued = 0;
      while (CURLMsg* message = curl_multi_info_read(multi, &queued)) {
        if (message->msg == CURLMSG_DONE) result = message->data.result;
      }
      break;
    }
    // One deadline covers both limits: connecting, then each quiet stretch.
    auto now = Clock::now();
    if (now >= c.deadline) {
      timed_out = true;
      break;
    }
    if (c.received) {
      attempts_ = 0;
      c.received = false;
    }
    auto wait = std::chrono::duration_cast<Ms>(c.deadline - now) + Ms{1};
    curl_multi_poll(multi, nullptr, 0, static_cast<int>(std::min<int64_t>(wait.count(), 1000)), nullptr);
  }
  cancel_.remove(multi);
  curl_multi_remove_handle(multi, easy);
  curl_easy_cleanup(easy);
  curl_multi_cleanup(multi);
  curl_slist_free_all(headers);

  if (c.failure) return c.failure;
  if (timed_out) {
    return RequestError{c.opened ? "Flagbearer stream went quiet" : "Flagbearer stream connect timed out",
                        true, Ms{0}};
  }
  if (result != CURLE_OK) {
    return RequestError{std::string("Flagbearer stream failed: ") + curl_easy_strerror(result), true, Ms{0}};
  }
  return std::nullopt;
}

}  // namespace detail
}  // namespace flagbearer
