// The C++ SDK behind the same GET /flags and GET /flush routes as node-server.ts.
#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>

#include "flagbearer/flagbearer.hpp"

namespace {

std::atomic<bool> stopping{false};
std::atomic<int> active{0};

void on_signal(int) { stopping = true; }

std::string escape(const std::string& text) {
  std::string out;
  for (char c : text) {
    if (c == '"' || c == '\\') out += '\\';
    if (static_cast<unsigned char>(c) >= 0x20) out += c;
  }
  return out;
}

int hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

std::string decode(const std::string& text) {
  std::string out;
  for (size_t i = 0; i < text.size(); ++i) {
    if (text[i] == '+') {
      out += ' ';
    } else if (text[i] == '%' && i + 2 < text.size() && hex(text[i + 1]) >= 0 && hex(text[i + 2]) >= 0) {
      out += static_cast<char>(hex(text[i + 1]) * 16 + hex(text[i + 2]));
      i += 2;
    } else {
      out += text[i];
    }
  }
  return out;
}

// The decoded value of name in a query string, if present.
bool param(const std::string& query, const std::string& name, std::string& value) {
  size_t start = 0;
  while (start <= query.size()) {
    size_t end = query.find('&', start);
    if (end == std::string::npos) end = query.size();
    std::string pair = query.substr(start, end - start);
    size_t equals = pair.find('=');
    if (decode(pair.substr(0, equals)) == name) {
      value = equals == std::string::npos ? "" : decode(pair.substr(equals + 1));
      return true;
    }
    start = end + 1;
  }
  return false;
}

void reply(int socket, int status, const std::string& body) {
  std::string response = "HTTP/1.1 " + std::to_string(status) +
                         " X\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                         "Connection: close\r\nContent-Length: " +
                         std::to_string(body.size()) + "\r\n\r\n" + body;
  size_t sent = 0;
  while (sent < response.size()) {
    ssize_t n = ::send(socket, response.data() + sent, response.size() - sent, 0);
    if (n <= 0) return;
    sent += static_cast<size_t>(n);
  }
}

void handle(int socket, flagbearer::Client& flags) {
  std::string request;
  char buffer[4096];
  while (request.find("\r\n\r\n") == std::string::npos) {
    ssize_t n = ::recv(socket, buffer, sizeof buffer, 0);
    if (n <= 0) return;
    request.append(buffer, static_cast<size_t>(n));
  }
  std::string line = request.substr(0, request.find("\r\n"));
  std::string target = line.substr(line.find(' ') + 1);
  target = target.substr(0, target.find(' '));
  std::string path = target.substr(0, target.find('?'));
  std::string query = target.find('?') == std::string::npos ? "" : target.substr(target.find('?') + 1);

  // Uploads pending exposures now, so a test sees whether the server took them.
  if (path == "/flush") {
    try {
      flags.flush();
      return reply(socket, 200, "{\"flushed\":true}");
    } catch (const std::exception& error) {
      return reply(socket, 502, "{\"error\":\"" + escape(error.what()) + "\"}");
    }
  }
  if (path != "/flags") return reply(socket, 404, "{\"error\":\"Not found.\"}");
  std::string flag;
  std::string fallback = "false";
  param(query, "default", fallback);
  if (!param(query, "flag", flag) || flag.empty() || (fallback != "true" && fallback != "false")) {
    return reply(socket, 400, "{\"error\":\"Provide flag, and optionally default=true or default=false.\"}");
  }
  flagbearer::User user;
  std::string value;
  if (param(query, "userKey", value)) user.key = value;
  if (param(query, "email", value)) user.email = value;
  // Local evaluation: this request does not call Flagbearer's API.
  bool enabled = flags.is_enabled(flag, user, fallback == "true");
  reply(socket, 200, std::string("{\"flag\":\"") + escape(flag) + "\",\"enabled\":" + (enabled ? "true" : "false") + "}");
}

}  // namespace

int main() {
  const char* key = std::getenv("FLAGBEARER_SERVER_KEY");
  if (!key || !*key) {
    std::fprintf(stderr, "The integration fixture requires FLAGBEARER_SERVER_KEY.\n");
    return 1;
  }
  flagbearer::Options options;
  if (const char* url = std::getenv("FLAGBEARER_URL"); url && *url) options.url = url;
  options.debug = true;

  // One client for the whole server, not one per HTTP request.
  auto flags = flagbearer::Client::create(key, options);
  try {
    flags->sync(std::chrono::seconds(10));
    std::printf("Initial Flagbearer ruleset loaded.\n");
  } catch (const std::exception& error) {
    std::printf("Initial sync failed; serving defaults while the SDK recovers. %s\n", error.what());
  }

  std::signal(SIGTERM, on_signal);
  std::signal(SIGINT, on_signal);
  std::signal(SIGPIPE, SIG_IGN);

  int listener = ::socket(AF_INET, SOCK_STREAM, 0);
  int yes = 1;
  setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  const char* port = std::getenv("PORT");
  address.sin_port = htons(static_cast<uint16_t>(port ? std::atoi(port) : 0));
  if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof address) != 0 || ::listen(listener, 64) != 0) {
    std::perror("listen");
    return 1;
  }
  socklen_t length = sizeof address;
  getsockname(listener, reinterpret_cast<sockaddr*>(&address), &length);
  std::printf("C++ SDK fixture listening on http://127.0.0.1:%d\n", ntohs(address.sin_port));
  std::fflush(stdout);

  while (!stopping) {
    pollfd ready{listener, POLLIN, 0};
    if (::poll(&ready, 1, 100) <= 0) continue;
    int socket = ::accept(listener, nullptr, nullptr);
    if (socket < 0) continue;
    ++active;
    std::thread([socket, &flags] {
      handle(socket, *flags);
      ::close(socket);
      --active;
    }).detach();
  }
  ::close(listener);
  // Let requests in progress finish before the client goes away.
  for (int i = 0; i < 50 && active > 0; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
  flags->close();
  return 0;
}
