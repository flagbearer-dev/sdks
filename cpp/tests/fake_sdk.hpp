// A fake SDK server for the client tests: the three SDK routes over plain HTTP.
// Tests set what it returns and read what it got.
#pragma once

#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct Rule {
  std::string key;
  bool enabled = true;
  int rollout_bps = 10000;
};

class FakeSdk {
 public:
  FakeSdk();
  ~FakeSdk();

  std::string url() const { return "http://127.0.0.1:" + std::to_string(port_); }
  void announce(int version);
  void ping();
  void set(const std::function<void(FakeSdk&)>& change);
  bool wait_for_stream(int milliseconds);

  // Guarded by mutex(); change with set().
  int version = 1;
  std::string environment_id = "env-1";
  std::vector<Rule> flags{{"checkout", true, 10000}};
  std::string ruleset_body;  // overrides the ruleset response when set
  std::deque<int> ruleset_statuses;
  int stream_status = 200;
  std::deque<int> exposure_statuses;
  bool redirect_ruleset = false;

  // Read with snapshot().
  std::vector<std::string> ruleset_requests;  // each request's If-None-Match
  std::vector<std::string> exposures;         // upload bodies
  int stream_connections = 0;

  template <typename T>
  T snapshot(const std::function<T(const FakeSdk&)>& read) {
    std::lock_guard<std::mutex> lock(mutex_);
    return read(*this);
  }

 private:
  void accept_loop();
  void serve(int socket);
  void stream(int socket);
  std::string ruleset_json() const;

  int listener_ = -1;
  int port_ = 0;
  std::atomic<bool> stopping_{false};
  std::mutex mutex_;
  std::condition_variable changed_;
  std::deque<std::string> events_;
  bool stream_opened_ = false;
  std::thread acceptor_;
  std::mutex connections_mutex_;
  std::vector<std::thread> connections_;
};

// Polls a condition the SDK reaches on its own threads.
bool wait_until(const std::function<bool()>& condition, int milliseconds = 3000);
