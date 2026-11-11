import Foundation

private let windowMs: Int64 = 30_000
// One upload per bucket; sending more often only splits a bucket into more uploads.
private let flushMs: Int64 = 30_000
private let maxRecords = 1000
private let batchSize = 100

private final class Exposure {
    let flagKey: String
    let result: Bool
    let reason: String
    let configurationVersion: Int?
    let bucket: String
    var checkCount = 1
    let firstCheckedAt: String
    var lastCheckedAt: String

    init(flagKey: String, details: Evaluation, bucket: String, at: String) {
        self.flagKey = flagKey
        result = details.value
        reason = details.reason
        configurationVersion = details.configurationVersion
        self.bucket = bucket
        firstCheckedAt = at
        lastCheckedAt = at
    }
}

// One upload's contents, fixed when sealed, so a retry sends exactly the same
// batch and the server can recognize it by batchId.
private final class SealedBatch {
    let batchId: String
    let user: User
    var events: [Exposure] = []

    init(batchId: String, user: User) {
        self.batchId = batchId
        self.user = user
    }
}

/// Counts flag checks and uploads them in batches, so the dashboard can show
/// which flags are in use. Checks are counted per flag, user, value and
/// 30-second window; nothing is sent per check.
final class ExposureReporter {
    private let platform: Platform
    private let url: String
    private let key: String
    private let appVersion: String?
    private let lock = NSLock()
    private let reporterId: String
    private var active: [String: (user: User, event: Exposure)] = [:]
    private var order: [String] = []
    private var sealed: [SealedBatch] = []
    private var sealedEvents = 0
    private var droppedChecks: Int64 = 0
    private var reportedDroppedChecks: Int64 = 0
    private var sending = false
    private var waiting: [(Error?) -> Void] = []
    private var closed = false
    private var retryAt: Int64 = 0
    private var failures = 0
    private var cancelTimer: (() -> Void)?

    init(platform: Platform, url: String, key: String, appVersion: String?) {
        self.platform = platform
        self.url = url
        self.key = key
        self.appVersion = appVersion
        reporterId = platform.randomId()
        tick()
    }

    private func tick() {
        cancelTimer = platform.schedule(after: flushMs) { [weak self] in
            guard let self, !self.locked({ self.closed }) else { return }
            if self.platform.now() >= self.locked({ self.retryAt }) { self.flush(nil) }
            self.tick()
        }
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private func iso(_ ms: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    func record(_ flagKey: String, user: User, details: Evaluation) {
        locked {
            if closed { return }
            if flagKey.isEmpty || flagKey.count > 256 || (user.key?.count ?? 0) > 256 {
                droppedChecks += 1
                return
            }
            let now = platform.now()
            let bucket = iso(now / windowMs * windowMs)
            var email = user.email.flatMap { $0.count <= 254 ? $0 : nil }?
                .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if email?.isEmpty == true { email = nil }
            let userKey = user.key.flatMap { $0.isEmpty ? nil : $0 }
            let version = details.configurationVersion.map(String.init) ?? "null"
            let id = [flagKey, userKey ?? "", email ?? "", String(details.value), details.reason, version, bucket]
                .joined(separator: "\u{0}")
            if let existing = active[id]?.event, existing.checkCount < 1_000_000_000 {
                existing.checkCount += 1
                existing.lastCheckedAt = iso(now)
                return
            }
            if active[id] != nil || active.count + sealedEvents >= maxRecords {
                droppedChecks += 1
                return
            }
            let event = Exposure(flagKey: flagKey, details: details, bucket: bucket, at: iso(now))
            active[id] = (User(key: userKey, email: email), event)
            order.append(id)
        }
    }

    // identify() can switch users mid-session, so a batch never mixes users.
    private func seal() {
        var open: [User: SealedBatch] = [:]
        for id in order {
            guard let (user, event) = active[id] else { continue }
            var batch = open[user]
            if batch == nil || batch!.events.count >= batchSize {
                batch = SealedBatch(batchId: platform.randomId(), user: user)
                open[user] = batch
                sealed.append(batch!)
            }
            batch!.events.append(event)
            sealedEvents += 1
        }
        active.removeAll()
        order.removeAll()
    }

    private func settle(_ batch: SealedBatch) {
        guard sealed.first === batch else { return }
        sealed.removeFirst()
        sealedEvents -= batch.events.count
    }

    /// Uploads everything pending. `done` gets nil, or why the upload failed.
    func flush(_ done: ((Error?) -> Void)?) {
        let start: Bool = locked {
            if let done { waiting.append(done) }
            if sending { return false }
            sending = true
            return true
        }
        guard start else { return }
        platform.io { [self] in
            var failure: Error?
            do { try send() } catch { failure = error }
            let finished: [(Error?) -> Void] = locked {
                sending = false
                if failure == nil {
                    failures = 0
                } else {
                    failures += 1
                    let backoff = min(60_000, Int64(1000) << Int64(min(failures, 6)))
                    retryAt = max(retryAt, platform.now() + backoff)
                }
                defer { waiting.removeAll() }
                return waiting
            }
            finished.forEach { $0(failure) }
        }
    }

    private func send() throws {
        locked { seal() }
        while true {
            let next: (batch: SealedBatch, dropped: Int64)? = locked {
                if sealed.isEmpty && droppedChecks <= reportedDroppedChecks { return nil }
                // Only lost checks to report: an empty batch carries the count.
                let batch = sealed.first ?? SealedBatch(batchId: platform.randomId(), user: User())
                return (batch, droppedChecks)
            }
            guard let (batch, dropped) = next else { return }
            let checks = batch.events.reduce(Int64(0)) { $0 + Int64($1.checkCount) }
            let response = try platform.post(
                url: url,
                headers: ["Authorization": "Bearer \(key)"],
                body: body(batch, dropped: dropped),
                timeoutMs: 10_000
            )
            try locked {
                guard (200...299).contains(response.status) else {
                    if let retryAfter = response.retryAfter.flatMap(Double.init), retryAfter > 0 {
                        retryAt = max(retryAt, platform.now() + Int64(min(retryAfter, 3600) * 1000))
                    }
                    // The server will never take this batch, so stop resending it.
                    if (400...499).contains(response.status) && response.status != 429 {
                        droppedChecks += checks
                        settle(batch)
                        retryAt = platform.now() + 60_000
                    }
                    throw FlagbearerError("Exposure upload failed (\(response.status))")
                }
                let receipt = (try? JSONSerialization.jsonObject(with: response.body)) as? [String: Any]
                guard
                    let accepted = (receipt?["accepted"] as? NSNumber)?.int64Value,
                    let rejected = (receipt?["rejectedChecks"] as? NSNumber)?.int64Value,
                    (0...Int64(batch.events.count)).contains(accepted),
                    (0...checks).contains(rejected)
                else {
                    throw FlagbearerError("Invalid exposure upload receipt")
                }
                droppedChecks += rejected
                settle(batch)
                reportedDroppedChecks = max(reportedDroppedChecks, dropped)
            }
        }
    }

    private func body(_ batch: SealedBatch, dropped: Int64) -> Data {
        let events: [[String: Any]] = batch.events.map { event in
            [
                "flagKey": event.flagKey,
                "result": event.result,
                "reason": event.reason,
                "configurationVersion": event.configurationVersion.map { $0 as Any } ?? NSNull(),
                "bucket": event.bucket,
                "checkCount": event.checkCount,
                "firstCheckedAt": event.firstCheckedAt,
                "lastCheckedAt": event.lastCheckedAt,
            ]
        }
        var user: [String: Any] = [:]
        if let key = batch.user.key { user["key"] = key }
        if let email = batch.user.email { user["email"] = email }
        var body: [String: Any] = [
            "batchId": batch.batchId,
            "reporterId": reporterId,
            "user": user,
            "events": events,
            "droppedChecks": dropped,
            "sdk": "ios",
            "sdkVersion": platform.sdkVersion,
        ]
        if let appVersion { body["appVersion"] = String(appVersion.prefix(100)) }
        return (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
    }

    func close() {
        locked { closed = true }
        cancelTimer?()
        flush(nil)
    }
}
