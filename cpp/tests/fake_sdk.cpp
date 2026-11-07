#include "fake_sdk.hpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cctype>
#include <chrono>
#include <cstring>

namespace {

void send_all(int socket, const std::string& data) {
  size_t sent = 0;
  while (sent < data.size()) {
#ifdef MSG_NOSIGNAL
    ssize_t n = ::send(socket, data.data() + sent, data.size() - sent, MSG_NOSIGNAL);
#else
    ssize_t n = ::send(socket, data.data() + sent, data.size() - sent, 0);
#endif
    if (n <= 0) return;
    sent += static_cast<size_t>(n);
  }
}

void respond(int socket, int status, const std::string& body, const std::string& extra = "") {
  send_all(socket, "HTTP/1.1 " + std::to_string(status) + " X\r\nContent-Type: application/json\r\n" +
                       extra + "Content-Length: " + std::to_string(body.size()) +
                       "\r\nConnection: close\r\n\r\n" + body);
}

std::string header(const std::string& request, const std::string& name) {
  std::string lower;
  for (char c : request) lower += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  size_t at = lower.find("\r\n" + name + ":");
  if (at == std::string::npos) return "";
  size_t start = at + 2 + name.size() + 1;
  size_t end = request.find("\r\n", start);
  std::string value = request.substr(start, end - start);
  value.erase(0, value.find_first_not_of(' '));
  return value;
}

}  // namespace

FakeSdk::FakeSdk() {
  listener_ = ::socket(AF_INET, SOCK_STREAM, 0);
  int yes = 1;
  setsockopt(listener_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = 0;
  ::bind(listener_, reinterpret_cast<sockaddr*>(&address), sizeof address);
  ::listen(listener_, 64);
  socklen_t length = sizeof address;
  getsockname(listener_, reinterpret_cast<sockaddr*>(&address), &length);
  port_ = ntohs(address.sin_port);
  acceptor_ = std::thread([this] { accept_loop(); });
}

FakeSdk::~FakeSdk() {
  stopping_ = true;
  changed_.notify_all();
  if (acceptor_.joinable()) acceptor_.join();
  ::close(listener_);
  std::lock_guard<std::mutex> lock(connections_mutex_);
  for (auto& connection : connections_) connection.join();
}

void FakeSdk::set(const std::function<void(FakeSdk&)>& change) {
  std::lock_guard<std::mutex> lock(mutex_);
  change(*this);
}

void FakeSdk::announce(int version) {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    events_.push_back("event: version\ndata: {\"version\":" + std::to_string(version) + "}\n\n");
  }
  changed_.notify_all();
}

void FakeSdk::ping() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    events_.push_back("event: ping\ndata: {}\n\n");
  }
  changed_.notify_all();
}

bool FakeSdk::wait_for_stream(int milliseconds) {
  std::unique_lock<std::mutex> lock(mutex_);
  return changed_.wait_for(lock, std::chrono::milliseconds(milliseconds), [this] { return stream_opened_; });
}

void FakeSdk::accept_loop() {
  while (!stopping_) {
    pollfd ready{listener_, POLLIN, 0};
    if (::poll(&ready, 1, 20) <= 0) continue;
    int socket = ::accept(listener_, nullptr, nullptr);
    if (socket < 0) continue;
#ifdef SO_NOSIGPIPE
    int yes = 1;
    setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof yes);
#endif
    std::lock_guard<std::mutex> lock(connections_mutex_);
    connections_.emplace_back([this, socket] {
      serve(socket);
      ::close(socket);
    });
  }
}

std::string FakeSdk::ruleset_json() const {
  std::string flags_json = "[";
  for (size_t i = 0; i < flags.size(); ++i) {
    if (i > 0) flags_json += ",";
    flags_json += "{\"key\":\"" + flags[i].key + "\",\"enabled\":" + (flags[i].enabled ? "true" : "false") +
                  ",\"rolloutBps\":" + std::to_string(flags[i].rollout_bps) + ",\"overrideEmails\":[]}";
  }
  flags_json += "]";
  return "{\"environmentId\":\"" + environment_id + "\",\"version\":" + std::to_string(version) +
         ",\"flags\":" + flags_json + "}";
}

void FakeSdk::serve(int socket) {
  timeval timeout{2, 0};
  setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
  std::string request;
  char buffer[4096];
  while (request.find("\r\n\r\n") == std::string::npos) {
    ssize_t n = ::recv(socket, buffer, sizeof buffer, 0);
    if (n <= 0) return;
    request.append(buffer, static_cast<size_t>(n));
  }
  size_t header_end = request.find("\r\n\r\n") + 4;
  std::string length = header(request, "content-length");
  size_t body_length = length.empty() ? 0 : std::stoul(length);
  while (request.size() < header_end + body_length) {
    ssize_t n = ::recv(socket, buffer, sizeof buffer, 0);
    if (n <= 0) return;
    request.append(buffer, static_cast<size_t>(n));
  }
  std::string body = request.substr(header_end, body_length);
  std::string line = request.substr(0, request.find("\r\n"));
  std::string path = line.substr(line.find(' ') + 1);
  path = path.substr(0, path.find(' '));

  if (header(request, "authorization") != "Bearer fbs_test") return respond(socket, 401, "{}");

  if (path == "/v1/ruleset") {
    std::unique_lock<std::mutex> lock(mutex_);
    std::string tag = header(request, "if-none-match");
    ruleset_requests.push_back(tag);
    int status = 200;
    if (!ruleset_statuses.empty()) {
      status = ruleset_statuses.front();
      ruleset_statuses.pop_front();
    }
    std::string etag = "\"" + std::to_string(version) + "\"";
    std::string payload = ruleset_body.empty() ? ruleset_json() : ruleset_body;
    bool redirect = redirect_ruleset;
    lock.unlock();
    if (redirect) return respond(socket, 302, "{}", "Location: /v1/ruleset\r\n");
    if (status != 200) return respond(socket, status, "{}", "Retry-After: 0\r\n");
    if (tag == etag) return respond(socket, 304, "");
    return respond(socket, 200, payload, "ETag: " + etag + "\r\n");
  }
  if (path == "/v1/stream") {
    int status = 0;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      ++stream_connections;
      status = stream_status;
    }
    if (status != 200) return respond(socket, status, "{}");
    return stream(socket);
  }
  if (path == "/v1/exposures") {
    int status = 200;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      exposures.push_back(body);
      if (!exposure_statuses.empty()) {
        status = exposure_statuses.front();
        exposure_statuses.pop_front();
      }
    }
    if (status != 200) return respond(socket, status, "{}");
    size_t events = 0;
    for (size_t at = body.find("\"flagKey\""); at != std::string::npos; at = body.find("\"flagKey\"", at + 1)) ++events;
    return respond(socket, 200, "{\"accepted\":" + std::to_string(events) + ",\"rejectedChecks\":0}");
  }
  respond(socket, 404, "{}");
}

void FakeSdk::stream(int socket) {
  int current = 0;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    current = version;
  }
  send_all(socket,
           "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
           "Connection: close\r\n\r\nretry: 5000\n\nevent: version\ndata: {\"version\":" +
               std::to_string(current) + "}\n\n");
  {
    std::lock_guard<std::mutex> lock(mutex_);
    stream_opened_ = true;
  }
  changed_.notify_all();
  while (!stopping_) {
    std::string event;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      changed_.wait_for(lock, std::chrono::milliseconds(20), [this] { return stopping_ || !events_.empty(); });
      if (!events_.empty()) {
        event = events_.front();
        events_.pop_front();
      }
    }
    if (!event.empty()) send_all(socket, event);
    // Stop once the client hangs up.
    pollfd peer{socket, POLLIN, 0};
    if (::poll(&peer, 1, 0) > 0) {
      char probe;
      if (::recv(socket, &probe, 1, MSG_PEEK | MSG_DONTWAIT) <= 0) return;
    }
  }
}

bool wait_until(const std::function<bool()>& condition, int milliseconds) {
  auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(milliseconds);
  while (std::chrono::steady_clock::now() < deadline) {
    if (condition()) return true;
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  return condition();
}
