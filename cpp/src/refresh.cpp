#include "internal.hpp"

namespace flagbearer {
namespace detail {

Coordinator::Coordinator(Fetch fetch, Apply apply, std::function<void()> on_permanent,
                         Timings timings, DebugLog debug)
    : fetch_(std::move(fetch)),
      apply_(std::move(apply)),
      on_permanent_(std::move(on_permanent)),
      t_(timings),
      debug_(debug) {}

Coordinator::~Coordinator() {
  close();
  join();
}

void Coordinator::join() {
  if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
}

void Coordinator::start() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    ensure_polling();
  }
  thread_ = std::thread([this] { run(); });
}

void Coordinator::sync(Ms timeout) {
  auto waiter = std::make_shared<Waiter>();
  auto done = waiter->promise.get_future();
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) throw Error("Flagbearer client is closed", true);
    blocked_ = false;
    ensure_polling();
    waiters_.push_back(waiter);
    if (in_flight_) {
      debug_("Joining active refresh");
    } else {
      retry_at_.reset();
      requested_ = true;
    }
  }
  wake_.notify_all();
  if (done.wait_for(timeout) != std::future_status::ready) {
    throw Error("Flagbearer sync timed out", false);
  }
  done.get();
}

void Coordinator::refresh_in_background() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    background_locked();
  }
  wake_.notify_all();
}

void Coordinator::on_version(int64_t version) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (stopped_) return;
  debug_("Stream version received",
         {{"version", std::to_string(version)}, {"currentVersion", optional_field(version_)}});
  if (!wanted_ || version > *wanted_) wanted_ = version;
  if (behind() && !version_at_ && !in_flight_) {
    auto delay = Ms{static_cast<int64_t>(static_cast<double>(t_.max_version_refresh_delay.count()) *
                                         random_fraction())};
    version_at_ = Clock::now() + delay;
    wake_.notify_all();
  }
}

void Coordinator::set_streaming(bool connected, bool refresh) {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) return;
    if (streaming_ != connected) {
      debug_(connected ? "Stream connected" : "Stream disconnected; using polling");
    }
    streaming_ = connected;
    if (connected) {
      clear_polling();
      if (refresh) background_locked();
    } else {
      ensure_polling();
    }
  }
  wake_.notify_all();
}

void Coordinator::close() {
  std::vector<std::shared_ptr<Waiter>> waiters;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) return;
    stopped_ = true;
    debug_("Coordinator closed");
    waiters.swap(waiters_);
  }
  wake_.notify_all();
  for (auto& waiter : waiters) {
    waiter->promise.set_exception(std::make_exception_ptr(Error("Flagbearer client is closed", true)));
  }
}

// The helpers below expect mutex_ to be held.

bool Coordinator::behind() const { return wanted_ && (!version_ || *version_ < *wanted_); }

void Coordinator::background_locked() {
  if (stopped_ || blocked_ || retry_at_ || in_flight_) return;
  requested_ = true;
}

void Coordinator::ensure_polling() {
  if (stopped_ || blocked_ || streaming_ || poll_at_) return;
  Ms interval = jitter(t_.poll_interval, 0.1);
  debug_("Polling scheduled", {{"intervalMs", std::to_string(interval.count())}});
  poll_at_ = Clock::now() + interval;
}

void Coordinator::clear_polling() {
  if (poll_at_) debug_("Polling disabled");
  poll_at_.reset();
}

void Coordinator::schedule_retry(const RefreshFailure* failure) {
  Ms delay = backoff(attempt_, t_.retry_base, t_.max_retry);
  ++attempt_;
  if (failure) delay = std::max(delay, failure->retry_after);
  not_before_ = Clock::now() + delay;
  retry_at_ = not_before_;
  debug_("Refresh retry scheduled",
         {{"delayMs", std::to_string(delay.count())}, {"attempt", std::to_string(attempt_)}});
}

std::optional<Clock::duration> Coordinator::next_due(Clock::time_point now) const {
  if (requested_) return std::max(Clock::duration::zero(), not_before_ - now);
  std::optional<Clock::time_point> next;
  for (const auto& at : {retry_at_, poll_at_, version_at_}) {
    if (at && (!next || *at < *next)) next = at;
  }
  if (!next) return std::nullopt;
  return std::max(Clock::duration::zero(), *next - now);
}

bool Coordinator::take_due(Clock::time_point now) {
  if (version_at_ && *version_at_ <= now) {
    version_at_.reset();
    if (behind()) background_locked();
  }
  if (retry_at_ && *retry_at_ <= now) {
    retry_at_.reset();
    background_locked();
  }
  if (poll_at_ && *poll_at_ <= now) {
    poll_at_.reset();
    background_locked();
    ensure_polling();
  }
  return requested_ && now >= not_before_;
}

// The worker thread.

void Coordinator::run() {
  while (true) {
    std::vector<std::shared_ptr<Waiter>> waiters;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      while (true) {
        if (stopped_) return;
        auto now = Clock::now();
        if (take_due(now)) break;
        auto delay = next_due(now);
        if (delay) {
          wake_.wait_for(lock, *delay);
        } else {
          wake_.wait(lock);
        }
      }
      requested_ = false;
      in_flight_ = true;
      version_at_.reset();
      waiters.swap(waiters_);
    }

    std::exception_ptr error;
    try {
      refresh();
    } catch (...) {
      error = std::current_exception();
    }

    bool stopped = false;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      in_flight_ = false;
      // Sync calls that arrived during this fetch join it.
      for (auto& waiter : waiters_) waiters.push_back(waiter);
      waiters_.clear();
      stopped = stopped_;
    }
    for (auto& waiter : waiters) {
      if (error) {
        waiter->promise.set_exception(error);
      } else {
        waiter->promise.set_value();
      }
    }
    if (error && !stopped && waiters.empty()) {
      try {
        std::rethrow_exception(error);
      } catch (const std::exception& failure) {
        log_error(std::string("Flagbearer sync failed: ") + failure.what());
      } catch (...) {
      }
    }
  }
}

// One refresh, repeated while a stream announcement is still ahead of us.
void Coordinator::refresh() {
  while (true) {
    debug_("Refresh started");
    std::shared_ptr<const Ruleset> result;
    try {
      result = fetch_();
    } catch (const RefreshFailure& failure) {
      bool permanent = failure.is_permanent();
      {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_) throw;
        if (permanent) {
          debug_("Automatic refresh stopped after permanent failure");
          blocked_ = true;
          streaming_ = false;
          retry_at_.reset();
          clear_polling();
        } else {
          schedule_retry(&failure);
        }
      }
      if (permanent) on_permanent_();
      throw;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) return;
    if (!result) {
      debug_("Snapshot unchanged", {{"version", optional_field(version_)}});
      if (behind()) {
        // The backend is still behind: retry later rather than spin.
        schedule_retry(nullptr);
      } else {
        attempt_ = 0;
        not_before_ = Clock::time_point{};
      }
      return;
    }
    auto previous = version_;
    if (!previous || result->version >= *previous) {
      version_ = result->version;
      apply_(result);
      debug_("Snapshot applied", {{"version", std::to_string(result->version)},
                                  {"previousVersion", optional_field(previous)}});
    } else {
      debug_("Older snapshot ignored", {{"version", std::to_string(result->version)},
                                        {"currentVersion", std::to_string(*previous)}});
    }
    if (!behind()) {
      attempt_ = 0;
      not_before_ = Clock::time_point{};
      return;
    }
    if (previous && result->version <= *previous) {
      schedule_retry(nullptr);
      return;
    }
    // An announcement arrived during the request: fetch again now.
  }
}

}  // namespace detail
}  // namespace flagbearer
