#include <ctime>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

namespace {
constexpr int64_t kWindowMs = 30000;
constexpr size_t kMaxRecords = 1000;
constexpr size_t kBatchSize = 100;
constexpr int64_t kMaxCheckCount = 1000000000;
}  // namespace

std::string iso_millis(std::chrono::system_clock::time_point at) {
  auto ms = std::chrono::duration_cast<Ms>(at.time_since_epoch()).count();
  std::time_t seconds = static_cast<std::time_t>(ms / 1000);
  std::tm utc{};
  gmtime_r(&seconds, &utc);
  char date[32];
  std::strftime(date, sizeof date, "%Y-%m-%dT%H:%M:%S", &utc);
  char millis[8];
  std::snprintf(millis, sizeof millis, "%03d", static_cast<int>((ms % 1000 + 1000) % 1000));
  return std::string(date) + "." + millis + "Z";
}

ExposureReporter::ExposureReporter(std::string url, std::string key, std::string app_version,
                                   Timings timings)
    : url_(std::move(url)), key_(std::move(key)), t_(timings), reporter_id_(new_uuid()) {
  // Labels are at most 100 UTF-16 units; cut on a character boundary.
  size_t units = 0;
  size_t pos = 0;
  while (pos < app_version.size()) {
    size_t next = pos;
    uint32_t code = 0;
    next_code_point(app_version, next, code);
    units += code >= 0x10000 ? 2 : 1;
    if (units > 100) break;
    pos = next;
  }
  app_version_ = app_version.substr(0, pos);
}

ExposureReporter::~ExposureReporter() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    closed_ = true;
  }
  wake_.notify_all();
  if (thread_.joinable()) thread_.join();
}

void ExposureReporter::start() { thread_ = std::thread([this] { run(); }); }

void ExposureReporter::record(std::string_view flag, const User& user,
                              const Evaluation& details) noexcept {
  try {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return;
    std::string key = user.key.value_or("");
    if (flag.empty() || js_length(flag) > 256 || js_length(key) > 256) {
      ++dropped_;
      return;
    }
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<Ms>(now.time_since_epoch()).count();
    std::string bucket = iso_millis(std::chrono::system_clock::time_point(Ms{ms / kWindowMs * kWindowMs}));
    std::string email;
    if (user.email && js_length(*user.email) <= 254) email = ascii_lower(js_trim(*user.email));
    Who who{key, email};
    std::string identity = json_quote(flag) + "\x1f" + json_quote(who.key) + "\x1f" +
                           json_quote(who.email) + "\x1f" + (details.value ? "1" : "0") + "\x1f" +
                           reason_name(details.reason) + "\x1f" + optional_field(details.version) +
                           "\x1f" + bucket;
    auto existing = active_.find(identity);
    if (existing != active_.end()) {
      auto& event = *existing->second.second;
      if (event.check_count < kMaxCheckCount) {
        ++event.check_count;
        event.last_checked_at = iso_millis(now);
      } else {
        ++dropped_;
      }
      return;
    }
    if (active_.size() + sealed_events_ >= kMaxRecords) {
      ++dropped_;
      return;
    }
    auto event = std::make_shared<Event>(Event{std::string(flag), details.value, details.reason,
                                               details.version, bucket, 1, iso_millis(now),
                                               iso_millis(now)});
    active_.emplace(identity, std::make_pair(who, event));
    order_.push_back(identity);
  } catch (...) {
    try {
      std::lock_guard<std::mutex> lock(mutex_);
      ++dropped_;
    } catch (...) {
    }
  }
}

void ExposureReporter::run() {
  std::unique_lock<std::mutex> lock(mutex_);
  while (!closed_) {
    wake_.wait_for(lock, jitter(t_.exposure_flush_interval, 0.1), [this] { return closed_; });
    if (closed_) return;
    if (Clock::now() < retry_at_) continue;
    lock.unlock();
    try {
      flush();
    } catch (...) {
      // Background uploads retry later.
    }
    lock.lock();
  }
}

void ExposureReporter::flush() {
  std::lock_guard<std::mutex> sending(send_mutex_);
  try {
    send();
  } catch (...) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++failures_;
    Ms delay = std::min(Ms{60000}, Ms{1000} * (int64_t{1} << std::min(failures_, 6)));
    retry_at_ = std::max(retry_at_, Clock::now() + delay);
    throw;
  }
}

void ExposureReporter::close() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return;
    closed_ = true;
  }
  wake_.notify_all();
  if (thread_.joinable()) thread_.join();
  flush();
}

// Moves active checks into batches. A batch never mixes users. Expects mutex_ held.
void ExposureReporter::seal() {
  std::vector<std::pair<Who, std::shared_ptr<Batch>>> open;
  for (const auto& identity : order_) {
    auto& [who, event] = active_.at(identity);
    std::shared_ptr<Batch> batch;
    for (auto& [user, candidate] : open) {
      if (user == who) batch = candidate;
    }
    if (!batch || batch->events.size() >= kBatchSize) {
      batch = std::make_shared<Batch>(Batch{new_uuid(), who, {}});
      bool replaced = false;
      for (auto& entry : open) {
        if (entry.first == who) {
          entry.second = batch;
          replaced = true;
        }
      }
      if (!replaced) open.emplace_back(who, batch);
      sealed_.push_back(batch);
    }
    batch->events.push_back(event);
    ++sealed_events_;
  }
  active_.clear();
  order_.clear();
}

void ExposureReporter::settle(const std::shared_ptr<Batch>& batch) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!sealed_.empty() && sealed_.front() == batch) {
    sealed_.erase(sealed_.begin());
    sealed_events_ -= batch->events.size();
  }
}

void ExposureReporter::send() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    seal();
  }
  while (true) {
    std::shared_ptr<Batch> batch;
    int64_t dropped = 0;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (sealed_.empty() && dropped_ <= reported_dropped_) break;
      // Only lost checks to report: an empty batch carries the count.
      batch = sealed_.empty() ? std::make_shared<Batch>(Batch{new_uuid(), {}, {}}) : sealed_.front();
      dropped = dropped_;
    }

    int64_t checks = 0;
    std::string events = "[";
    for (size_t i = 0; i < batch->events.size(); ++i) {
      const auto& e = *batch->events[i];
      checks += e.check_count;
      if (i > 0) events += ",";
      events += "{\"flagKey\":" + json_quote(e.flag) + ",\"result\":" + (e.result ? "true" : "false") +
                ",\"reason\":\"" + reason_name(e.reason) + "\",\"configurationVersion\":" +
                (e.version ? std::to_string(*e.version) : "null") + ",\"bucket\":\"" + e.bucket +
                "\",\"checkCount\":" + std::to_string(e.check_count) + ",\"firstCheckedAt\":\"" +
                e.first_checked_at + "\",\"lastCheckedAt\":\"" + e.last_checked_at + "\"}";
    }
    events += "]";
    std::string user = "{";
    if (!batch->user.key.empty()) user += "\"key\":" + json_quote(batch->user.key);
    if (!batch->user.email.empty()) {
      if (user.size() > 1) user += ",";
      user += "\"email\":" + json_quote(batch->user.email);
    }
    user += "}";
    std::string body = "{\"batchId\":\"" + batch->id + "\",\"reporterId\":\"" + reporter_id_ +
                       "\",\"user\":" + user + ",\"events\":" + events +
                       ",\"droppedChecks\":" + std::to_string(dropped) +
                       ",\"sdk\":\"cpp\",\"sdkVersion\":\"" + kVersion + "\"";
    if (!app_version_.empty()) body += ",\"appVersion\":" + json_quote(app_version_);
    body += "}";

    Request request;
    request.method = "POST";
    request.url = url_;
    request.headers = {"Authorization: Bearer " + key_, "Content-Type: application/json"};
    request.body = body;
    request.timeout = t_.exposure_timeout;
    auto outcome = send_request(request);
    if (auto* failure = std::get_if<RequestError>(&outcome)) throw Error(failure->message, false);
    const auto& response = std::get<Response>(outcome);
    if (response.status < 200 || response.status >= 300) {
      bool refused = response.status >= 400 && response.status < 500 && response.status != 429;
      {
        std::lock_guard<std::mutex> lock(mutex_);
        Ms wait = parse_retry_after(response.retry_after);
        if (wait > Ms{0}) retry_at_ = std::max(retry_at_, Clock::now() + std::min(wait, Ms{3600000}));
        if (refused) {
          // The server won't take this batch, so retrying can't help.
          dropped_ += checks;
          retry_at_ = Clock::now() + Ms{60000};
        }
      }
      if (refused) settle(batch);
      throw Error("Exposure upload failed (" + std::to_string(response.status) + ")", !refused);
    }

    auto receipt = parse_json(response.body);
    auto accepted = receipt ? (receipt->get("accepted") ? receipt->get("accepted")->as_integer() : std::nullopt)
                            : std::nullopt;
    auto rejected = receipt ? (receipt->get("rejectedChecks") ? receipt->get("rejectedChecks")->as_integer()
                                                              : std::nullopt)
                            : std::nullopt;
    if (!accepted || !rejected || *accepted < 0 || *accepted > static_cast<int64_t>(batch->events.size()) ||
        *rejected < 0 || *rejected > checks) {
      throw Error("Invalid exposure upload receipt", false);
    }
    {
      std::lock_guard<std::mutex> lock(mutex_);
      dropped_ += *rejected;
      reported_dropped_ = std::max(reported_dropped_, dropped);
    }
    settle(batch);
  }
  std::lock_guard<std::mutex> lock(mutex_);
  failures_ = 0;
}

std::variant<Response, RequestError> ExposureReporter::send_request(const Request& request) {
  return detail::send(request, cancel_);
}

}  // namespace detail
}  // namespace flagbearer
