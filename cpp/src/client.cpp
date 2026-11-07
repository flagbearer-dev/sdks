#include <cctype>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

namespace {

// The URL with any trailing slash removed, or nullopt unless it's HTTP or HTTPS
// with a host.
std::optional<std::string> base_url(const std::string& url) {
  std::string lower = ascii_lower(url);
  size_t scheme_end = lower.find("://");
  if (scheme_end == std::string::npos) return std::nullopt;
  std::string scheme = lower.substr(0, scheme_end);
  if (scheme != "http" && scheme != "https") return std::nullopt;
  size_t host_start = scheme_end + 3;
  size_t host_end = url.find_first_of("/?#", host_start);
  if (host_end == host_start || host_start >= url.size()) return std::nullopt;
  std::string base = url.substr(0, url.find_first_of("?#"));
  while (!base.empty() && base.back() == '/' && base.size() > host_start) base.pop_back();
  return base;
}

}  // namespace

ClientImpl::ClientImpl(const std::string& key, const Options& options, const Timings& timings)
    : t_(timings) {
  bool has_space = false;
  for (char c : key) has_space = has_space || std::isspace(static_cast<unsigned char>(c));
  if (key.empty() || has_space) throw std::invalid_argument("A nonempty server SDK key is required");
  auto base = base_url(options.url);
  if (!base) throw std::invalid_argument("Flagbearer url must use HTTP or HTTPS");
  global_curl_init();

  DebugLog debug(options.debug);
  ruleset_url_ = *base + "/v1/ruleset";
  headers_ = {"Authorization: Bearer " + key};

  reporter_ = std::make_unique<ExposureReporter>(*base + "/v1/exposures", key, options.app_version, t_);
  coordinator_ = std::make_unique<Coordinator>(
      [this] { return fetch(); },
      [this](std::shared_ptr<const Ruleset> next) {
        std::lock_guard<std::mutex> lock(ruleset_mutex_);
        ruleset_ = std::move(next);
      },
      [this] { stream_->close(); }, t_, debug);

  StreamOptions stream;
  stream.url = *base + "/v1/stream";
  stream.headers = headers_;
  stream.timings = t_;
  stream.debug = debug;
  stream.on_version = [this](int64_t version) { coordinator_->on_version(version); };
  stream.on_open = [this] {
    // After a reconnect, refetch in case an announcement was missed.
    coordinator_->set_streaming(true, connected_.exchange(true));
  };
  stream.on_disconnect = [this] { coordinator_->set_streaming(false, false); };
  stream_ = std::make_unique<VersionStream>(std::move(stream));

  reporter_->start();
  coordinator_->start();
  stream_->start();
  coordinator_->refresh_in_background();
}

ClientImpl::~ClientImpl() { close(); }

void ClientImpl::sync(Ms timeout) { coordinator_->sync(timeout); }

bool ClientImpl::is_enabled(std::string_view flag, const User& user, bool fallback) noexcept {
  Evaluation details{fallback, Reason::Fallback, std::nullopt};
  try {
    std::shared_ptr<const Ruleset> current;
    {
      std::lock_guard<std::mutex> lock(ruleset_mutex_);
      current = ruleset_;
    }
    if (current) {
      auto found = current->flags.find(std::string(flag));
      const FlagRule* rule = found == current->flags.end() ? nullptr : &found->second;
      details = evaluate(rule, user, current->version, fallback);
    }
  } catch (...) {
    // A bad input falls back to the default.
  }
  reporter_->record(flag, user, details);
  return details.value;
}

void ClientImpl::flush() { reporter_->flush(); }

void ClientImpl::close() noexcept {
  std::call_once(close_once_, [this] {
    // Wake and stop everything first, then wait for the threads.
    coordinator_->close();
    fetch_cancel_.cancel();
    stream_->close();
    coordinator_->join();
    stream_->join();
    try {
      reporter_->close();
    } catch (const std::exception& error) {
      log_error(std::string("Flagbearer final exposure upload failed: ") + error.what());
    } catch (...) {
    }
  });
}

std::shared_ptr<const Ruleset> ClientImpl::fetch() {
  std::shared_ptr<const Ruleset> current;
  {
    std::lock_guard<std::mutex> lock(ruleset_mutex_);
    current = ruleset_;
  }
  Request request;
  request.url = ruleset_url_;
  request.headers = headers_;
  if (current) request.headers.push_back("If-None-Match: \"" + std::to_string(current->version) + "\"");
  request.timeout = t_.request_timeout;
  auto outcome = send(request, fetch_cancel_);
  if (auto* failure = std::get_if<RequestError>(&outcome)) throw RefreshFailure(*failure);
  const auto& response = std::get<Response>(outcome);
  if (response.status == 304) return nullptr;
  if (response.status < 200 || response.status >= 300) throw RefreshFailure(status_error(response));
  auto next = parse_ruleset(response.body);
  if (!next) throw RefreshFailure(RequestError{"Invalid ruleset response", false, Ms{0}});
  if (current && next->environment_id != current->environment_id) {
    throw RefreshFailure(RequestError{"Ruleset environment changed unexpectedly", false, Ms{0}});
  }
  return std::make_shared<const Ruleset>(std::move(*next));
}

}  // namespace detail

Client::Client(std::unique_ptr<detail::ClientImpl> impl) : impl_(std::move(impl)) {}
Client::~Client() { close(); }

std::unique_ptr<Client> Client::create(const std::string& key, Options options) {
  return create_for_tests(key, std::move(options), detail::Timings{});
}

std::unique_ptr<Client> Client::create_for_tests(const std::string& key, Options options,
                                                 const detail::Timings& timings) {
  auto impl = std::make_unique<detail::ClientImpl>(key, options, timings);
  return std::unique_ptr<Client>(new Client(std::move(impl)));
}

void Client::sync(std::chrono::milliseconds timeout) { impl_->sync(timeout); }

bool Client::is_enabled(std::string_view flag, const User& user, bool default_value) noexcept {
  return impl_->is_enabled(flag, user, default_value);
}

void Client::flush() { impl_->flush(); }

void Client::close() noexcept {
  if (impl_) impl_->close();
}

}  // namespace flagbearer
