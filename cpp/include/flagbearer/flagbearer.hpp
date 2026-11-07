// Flagbearer server SDK for C++. It downloads an environment's rules once and
// checks flags locally, so a flag check never waits on the network.
#pragma once

#include <chrono>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace flagbearer {

// The SDK's version, sent with exposure reports.
inline constexpr const char* kVersion = "0.1.0";

// The production SDK address.
inline constexpr const char* kDefaultUrl = "https://sdk.flagbearer.dev";

// Who a flag is checked for. Both fields are optional; empty counts as unset.
struct User {
  std::optional<std::string> key;
  std::optional<std::string> email;
};

struct Options {
  // The SDK server address, for staging or local development.
  std::string url = kDefaultUrl;
  // Logs stream connections, received versions, refreshes, retries and applied
  // rules to stderr. Logs never include keys, user data, flag names or rules.
  bool debug = false;
  // Labels which deployment of your app reported each check.
  std::string app_version;
};

// A failed request. Permanent failures, such as a revoked key or invalid rules,
// stop automatic refreshes until sync is called again.
class Error : public std::runtime_error {
 public:
  Error(const std::string& message, bool permanent)
      : std::runtime_error(message), permanent_(permanent) {}
  bool is_permanent() const noexcept { return permanent_; }

 private:
  bool permanent_;
};

namespace detail {
struct Timings;
class ClientImpl;
}  // namespace detail

// Checks flags against the rules in memory. Create one per process and keep it
// for the life of the process. Every method is safe to call from any thread.
class Client {
 public:
  // Starts fetching rules and streaming updates in the background, without
  // blocking. Throws std::invalid_argument for an empty key, a key containing
  // whitespace, or a url that isn't HTTP or HTTPS.
  static std::unique_ptr<Client> create(const std::string& key, Options options = {});

  ~Client();
  Client(const Client&) = delete;
  Client& operator=(const Client&) = delete;

  // Waits up to timeout for the latest rules. Throws flagbearer::Error when the
  // request fails or the timeout passes; the client keeps retrying in the
  // background either way.
  void sync(std::chrono::milliseconds timeout);

  // Checks a flag using only the rules in memory. Never blocks on the network.
  // default_value is returned until the first rules arrive, and when the flag
  // doesn't exist or can't be evaluated.
  bool is_enabled(std::string_view flag, const User& user, bool default_value) noexcept;

  // Uploads pending exposure reports. Throws flagbearer::Error if an upload fails.
  void flush();

  // Stops streaming and background threads, then uploads pending exposures.
  // Safe to call more than once; rules in memory stay usable after it.
  void close() noexcept;

  // For tests: the same as create, with shorter delays.
  static std::unique_ptr<Client> create_for_tests(const std::string& key, Options options,
                                                  const detail::Timings& timings);

 private:
  explicit Client(std::unique_ptr<detail::ClientImpl> impl);
  std::unique_ptr<detail::ClientImpl> impl_;
};

}  // namespace flagbearer
