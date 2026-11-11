import Foundation

let defaultURL = "https://sdk.flagbearer.dev"

private let requestTimeoutMs = 5000
private let pollIntervalMs: Double = 30_000
private let maxRetryMs: Int64 = 30_000
// resume() refetches only when the last successful check is older than this.
private let resumeStaleMs: Int64 = 15_000
// Spreads refetches out so a flag change doesn't hit the API all at once.
private let maxVersionRefreshDelayMs: Double = 2000
// In the background this long, the client drops its stream and stops polling
// until the app is back.
private let idleHiddenMs: Int64 = 5 * 60_000
// Cached flags older than this are ignored, so a flag turned off doesn't stay
// on for long on a device that's been offline.
private let maxCacheAgeMs: Int64 = 7 * 86_400_000
private let anonymousIdKey = "flagbearer:anon"
private let cacheKey = "flagbearer:flags"
private let uuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
private let reasons: Set<String> = ["off", "tester", "rollout", "fallback"]

struct Options {
    var clientKey: String
    var url: String
    var user: User
    var debug: Bool
    var appVersion: String?
    var cacheFlags: Bool
}

private struct Snapshot {
    var version: Int
    var flags: [String: Bool]
    var details: [String: Evaluation]?
    var json: String
}

// What isEnabled reads. Replaced whole, so any thread can read a copy.
private struct View {
    var user: User
    var status: FlagsStatus = .loading
    var flags: [String: Bool] = [:]
    var details: [String: Evaluation] = [:]
    // Set only by flags from the server; cached flags leave it nil, so the
    // next fetch asks for everything.
    var version: Int?
    // True while `details` lists every flag in the environment, so a flag
    // missing from it doesn't exist.
    var knowsEveryFlag = false
}

// What's wrong with a client key, when that can be told without a request.
private func clientKeyProblem(_ key: String) -> String? {
    if key.isEmpty {
        return "No client key was given. Pass your environment's client key (fbc_...) as clientKey."
    }
    if key.contains(where: \.isWhitespace) {
        return "The client key contains a space or line break. Copy it again from the Flagbearer dashboard."
    }
    if key.hasPrefix("fbs_") {
        return "This is a server key (fbs_), which is secret and must not ship in an app. Use the environment's client key (fbc_) instead."
    }
    return nil
}

// JSON true and false only: JSONSerialization would also read 0 and 1 as Bool.
private func bool(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
    return number.boolValue
}

private func integer(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    return Int(exactly: number.doubleValue)
}

private func parseSnapshot(_ text: String) -> Snapshot? {
    guard
        let json = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
        let version = integer(json["version"]), version >= 0,
        let rawFlags = json["flags"] as? [String: Any]
    else { return nil }
    var flags: [String: Bool] = [:]
    for (key, value) in rawFlags {
        guard let value = bool(value) else { return nil }
        flags[key] = value
    }
    var details: [String: Evaluation]?
    if let rawDetails = json["details"] as? [String: Any] {
        var parsed: [String: Evaluation] = [:]
        for (key, raw) in rawDetails {
            guard
                let detail = raw as? [String: Any],
                let value = bool(detail["value"]),
                let reason = detail["reason"] as? String, reasons.contains(reason)
            else { return nil }
            let configuration = detail["configurationVersion"]
            if let configuration, !(configuration is NSNull), integer(configuration) == nil { return nil }
            parsed[key] = Evaluation(value: value, reason: reason, configurationVersion: integer(configuration))
        }
        details = parsed
    }
    return Snapshot(version: version, flags: flags, details: details, json: text)
}

/// The client's logic, with no UIKit in it: fetches a user's flags, keeps
/// them fresh from the server's stream of changes (polling when there's no
/// stream), caches them for the next launch, and reports checks.
/// Every change to its state happens on the platform's one state queue.
final class Client {
    typealias Done = (Error?) -> Void

    private let options: Options
    private let platform: Platform
    private let origin: String
    // A bad key doesn't stop the app: it keeps running on default values, and
    // the log says why. No request is made with it.
    private let keyProblem: String?
    private let reporter: ExposureReporter
    private let lock = NSLock()

    // Guarded by `lock`, since isEnabled and subscribe are called from any thread.
    private var view: View
    private var listeners: [Int: () -> Void] = [:]
    private var nextListener = 0
    private var warnedFlags: Set<String> = []
    private var anonymousId: String?
    private var anonymousIdLoaded = false

    // Everything below is touched only on the state queue.
    private var closed = false
    // Goes up when the user changes, so answers for the previous user are dropped.
    private var generation = 0
    // Waiters on the request in flight, or nil when there's none.
    private var inFlight: [Done]?
    // Set by a failure retrying can't fix. Only sync() or identify() tries again.
    private var blocked = false
    private var paused = false
    private var retryAttempt = 0
    // Set only from a server's Retry-After, which coming back must not cut short.
    private var serverNotBefore: Int64 = 0
    private var lastCheckedAt = Int64.min / 2
    private var cancelPoll: (() -> Void)?
    private var cancelRetry: (() -> Void)?
    private var cancelHidden: (() -> Void)?

    // The live-update stream. While it's open, flags are fetched when it
    // announces a change, and polling stops.
    private var closeStream: (() -> Void)?
    private var streamId = 0
    private var streaming = false
    private var hasStreamed = false
    // Set when the server refuses the stream outright. Polling carries on.
    private var streamRefused = false
    private var streamAttempt = 0
    private var cancelStreamRetry: (() -> Void)?
    private var cancelVersionRefresh: (() -> Void)?
    // The newest version the stream has announced.
    private var wantedVersion: Int?
    private var eventName = "message"
    private var eventData = ""

    init(options: Options, platform: Platform) {
        let match = options.url.range(of: "^https?://[^/?#]+", options: [.regularExpression, .caseInsensitive])
        origin = match.map { String(options.url[$0]) } ?? ""
        self.options = options
        self.platform = platform
        // A bad address is handled like a bad key, since an initializer that
        // crashes or throws would take the app down with it.
        keyProblem = match == nil
            ? "The url given to Flagbearer must start with http:// or https://."
            : clientKeyProblem(options.clientKey)
        reporter = ExposureReporter(
            platform: platform, url: "\(origin)/v1/exposures",
            key: options.clientKey, appVersion: options.appVersion
        )
        view = View(user: options.user)
        platform.run { self.start(nil) }
    }

    private func current() -> View {
        lock.lock()
        defer { lock.unlock() }
        return view
    }

    var status: FlagsStatus { current().status }

    func isEnabled(_ flag: String, default defaultValue: Bool) -> Bool {
        let current = current()
        let known = current.flags[flag]
        let value = known ?? defaultValue
        let evaluation = current.details[flag]
        if evaluation == nil && known != nil { return value }
        if evaluation == nil && current.knowsEveryFlag { warnUnknown(flag) }
        reporter.record(
            flag,
            user: current.user,
            details: evaluation.map {
                Evaluation(value: value, reason: $0.reason, configurationVersion: $0.configurationVersion)
            } ?? Evaluation(value: value, reason: "fallback", configurationVersion: current.version)
        )
        return value
    }

    // Once per flag, in debug builds or with debug on: a misspelled or deleted
    // flag otherwise looks like one that's off.
    private func warnUnknown(_ flag: String) {
        guard options.debug || platform.development else { return }
        lock.lock()
        let first = warnedFlags.count < 100 && warnedFlags.insert(flag).inserted
        lock.unlock()
        guard first else { return }
        platform.warn(
            "No flag \"\(flag)\" exists in this environment, so isEnabled returns its default. Check the spelling, or create the flag."
        )
    }

    /// A plain copy for error reports. It reports no exposures.
    func allFlags() -> [String: Bool] { current().flags }

    func subscribe(_ listener: @escaping () -> Void) -> () -> Void {
        lock.lock()
        let id = nextListener
        nextListener += 1
        listeners[id] = listener
        lock.unlock()
        return { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.listeners[id] = nil
            self.lock.unlock()
        }
    }

    func sync(_ done: @escaping Done) {
        platform.run { self.refresh(done, manual: true) }
    }

    func identify(_ user: User, _ done: @escaping Done) {
        platform.run { [self] in
            if closed {
                done(FlagbearerError("Flagbearer client is closed"))
            } else if user == current().user {
                // The same user again changes nothing, so their flags stay as they are.
                done(nil)
            } else {
                generation += 1
                if let waiters = inFlight {
                    inFlight = nil
                    waiters.forEach { $0(FlagbearerError("The Flagbearer user changed before this finished")) }
                }
                cancelRetry?()
                cancelRetry = nil
                retryAttempt = 0
                publish(View(user: user))
                start(done)
            }
        }
    }

    func flush(_ done: @escaping Done) {
        reporter.flush(done)
    }

    /// The app is in the foreground again.
    func resume() {
        platform.run { [self] in
            cancelHidden?()
            cancelHidden = nil
            paused = false
            if closed || blocked { return }
            connectStream()
            if platform.now() - lastCheckedAt < resumeStaleMs {
                ensurePolling()
                return
            }
            // Coming back cancels the SDK's own backoff, never the server's Retry-After.
            if platform.now() >= serverNotBefore {
                cancelRetry?()
                cancelRetry = nil
                retryAttempt = 0
            }
            cancelPoll?()
            cancelPoll = nil
            refresh(nil, manual: false)
            ensurePolling()
        }
    }

    /// The app went to the background: upload what's pending. If it stays
    /// there, drop the stream and stop polling until it's back.
    func background() {
        platform.run { [self] in
            reporter.flush(nil)
            cancelHidden?()
            cancelHidden = platform.schedule(after: idleHiddenMs) { [self] in
                cancelHidden = nil
                paused = true
                disconnectStream()
                cancelPoll?()
                cancelPoll = nil
            }
        }
    }

    func close() {
        platform.run { [self] in
            if closed { return }
            closed = true
            lock.lock()
            listeners.removeAll()
            lock.unlock()
            cancelPoll?()
            cancelRetry?()
            cancelHidden?()
            cancelVersionRefresh?()
            disconnectStream()
            inFlight?.forEach { $0(FlagbearerError("Flagbearer client is closed")) }
            inFlight = nil
            reporter.close()
        }
    }

    // The current user's first load: their cached flags at once, then fresh ones.
    private func start(_ done: Done?) {
        let startedFor = generation
        let user = current().user
        if options.cacheFlags, platform.storage != nil {
            platform.io { [self] in
                let cached = readCache(user)
                platform.run { [self] in
                    // Fresh flags always win, even if the cache is slow to read.
                    guard let cached, !closed, generation == startedFor, current().version == nil else { return }
                    debug("Cached flags applied")
                    publish(View(user: user, status: .ready, flags: cached.flags, details: cached.details ?? [:]))
                }
            }
        }
        blocked = false
        refresh(done, manual: true)
        connectStream()
        ensurePolling()
    }

    private func refresh(_ done: Done?, manual: Bool) {
        if closed {
            done?(FlagbearerError("Flagbearer client is closed"))
            return
        }
        if inFlight != nil {
            if let done { inFlight!.append(done) }
            return
        }
        if !manual && (blocked || cancelRetry != nil) { return }
        if manual { blocked = false }
        cancelRetry?()
        cancelRetry = nil

        inFlight = done.map { [$0] } ?? []
        let requestedFor = generation
        let current = current()
        debug("Refresh started")
        platform.io { [self] in
            let result = Result { try fetch(user: current.user, knownVersion: current.version) }
            platform.run { [self] in finish(requestedFor, result) }
        }
    }

    private func finish(_ requestedFor: Int, _ result: Result<Snapshot?, Error>) {
        // identify() or close() already answered these waiters.
        if closed || requestedFor != generation { return }
        let waiters = inFlight ?? []
        inFlight = nil
        var failure: Error?
        switch result {
        case .success(let snapshot):
            lastCheckedAt = platform.now()
            let view = current()
            if let snapshot, view.version == nil || snapshot.version >= view.version! {
                publish(
                    View(
                        user: view.user, status: .ready, flags: snapshot.flags,
                        details: snapshot.details ?? [:], version: snapshot.version,
                        knowsEveryFlag: snapshot.details != nil
                    )
                )
                writeCache(view.user, snapshot)
                debug("Snapshot applied")
            }
            if isBehindWantedVersion() {
                // The server answered with less than the stream announced:
                // ask again later rather than spin.
                scheduleRetry(nil)
            } else {
                retryAttempt = 0
            }
            connectStream()
        case .failure(let error):
            failure = error
            var view = current()
            if view.status == .loading {
                view.status = .failed
                publish(view)
            }
            if let error = error as? FlagbearerError, !error.retryable {
                // Said once, without debug on: retrying can't fix this, and
                // silent defaults look like flags that are simply off.
                blocked = true
                cancelPoll?()
                cancelPoll = nil
                disconnectStream()
                platform.error(
                    "Flags have stopped updating and keep their current or default values until this is fixed. \(error.message)"
                )
            } else {
                // Being offline is ordinary on a phone, so this isn't an error.
                debug("Refresh failed; retrying: \(error)")
                scheduleRetry(error)
            }
        }
        waiters.forEach { $0(failure) }
        ensurePolling()
    }

    private func publish(_ next: View) {
        lock.lock()
        let previous = view
        view = next
        let hearing = previous.flags == next.flags && previous.status == next.status ? [] : Array(listeners.keys)
        lock.unlock()
        for id in hearing {
            platform.notify { [weak self] in
                guard let self else { return }
                // A listener removed since, or a closed client, hears nothing.
                self.lock.lock()
                let listener = self.listeners[id]
                self.lock.unlock()
                listener?()
            }
        }
    }

    private func ensurePolling() {
        if closed || blocked || paused || streaming || cancelPoll != nil { return }
        let interval = Int64(pollIntervalMs * (0.9 + platform.random() * 0.2))
        cancelPoll = platform.schedule(after: interval) { [self] in
            cancelPoll = nil
            refresh(nil, manual: false)
            ensurePolling()
        }
    }

    private func isBehindWantedVersion() -> Bool {
        guard let wanted = wantedVersion else { return false }
        guard let known = current().version else { return true }
        return known < wanted
    }

    private func connectStream() {
        if closed || blocked || paused || streamRefused || keyProblem != nil { return }
        if closeStream != nil || cancelStreamRetry != nil { return }
        streamId += 1
        let id = streamId
        eventName = "message"
        eventData = ""
        debug("Stream connecting")
        let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        let key = options.clientKey.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
        // Each stream's events are dropped once another has replaced it.
        let events = StreamEvents(
            open: { [self] in platform.run { [self] in if id == streamId { onStreamOpen() } } },
            line: { [self] text in platform.run { [self] in if id == streamId { onStreamLine(text) } } },
            closed: { [self] status, retryAfter in
                platform.run { [self] in if id == streamId { onStreamClosed(status, retryAfter) } }
            }
        )
        closeStream = platform.openStream(url: "\(origin)/v1/stream?clientKey=\(key)", events: events)
    }

    private func disconnectStream() {
        streamId += 1
        closeStream?()
        closeStream = nil
        cancelStreamRetry?()
        cancelStreamRetry = nil
        if streaming { debug("Stream closed") }
        streaming = false
    }

    private func onStreamOpen() {
        debug("Stream connected")
        streaming = true
        streamAttempt = 0
        cancelPoll?()
        cancelPoll = nil
        // Changes made while there was no stream weren't announced, so catch up.
        if hasStreamed { refresh(nil, manual: false) }
        hasStreamed = true
    }

    // Server-Sent Events: "event:" and "data:" lines, then a blank line.
    private func onStreamLine(_ text: String) {
        if text.isEmpty {
            if eventName == "version",
                let json = (try? JSONSerialization.jsonObject(with: Data(eventData.utf8))) as? [String: Any],
                let version = integer(json["version"]), version >= 0
            {
                onVersion(version)
            }
            eventName = "message"
            eventData = ""
            return
        }
        if text.hasPrefix(":") { return }
        let parts = text.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        let field = String(parts[0])
        var content = parts.count > 1 ? String(parts[1]) : ""
        if content.hasPrefix(" ") { content.removeFirst() }
        if field == "event" { eventName = content }
        if field == "data", eventData.count < 10_000 { eventData += content }
    }

    private func onVersion(_ version: Int) {
        wantedVersion = max(wantedVersion ?? 0, version)
        if !isBehindWantedVersion() || cancelVersionRefresh != nil { return }
        let delay = Int64(platform.random() * maxVersionRefreshDelayMs)
        cancelVersionRefresh = platform.schedule(after: delay) { [self] in
            cancelVersionRefresh = nil
            if isBehindWantedVersion() { refresh(nil, manual: false) }
        }
    }

    private func onStreamClosed(_ status: Int?, _ retryAfter: String?) {
        closeStream = nil
        streaming = false
        debug("Stream disconnected; using polling")
        ensurePolling()
        // A refusal that reconnecting can't fix. Polling still works, and the
        // next fetch reports a bad key.
        if let status, (400...499).contains(status), status != 408, status != 429 {
            streamRefused = true
            return
        }
        if closed || blocked || paused { return }
        // The server ends every stream after about half an hour; reconnecting
        // after 5 to 15 seconds is what it asks of browsers too.
        let base = min(60_000, Int64(5000) << Int64(min(streamAttempt, 4)))
        streamAttempt += 1
        let wait = (retryAfter.flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil } ?? 0) * 1000
        let delay = max(Int64(Double(base) * (1 + platform.random() * 2)), Int64(wait))
        cancelStreamRetry = platform.schedule(after: delay) { [self] in
            cancelStreamRetry = nil
            connectStream()
        }
    }

    private func scheduleRetry(_ failure: Error?) {
        let backoff = min(maxRetryMs, Int64(1000) << Int64(min(retryAttempt, 5)))
        retryAttempt += 1
        let jittered = min(maxRetryMs, Int64(Double(backoff) * (0.8 + platform.random() * 0.4)))
        let retryAfter = (failure as? FlagbearerError)?.retryAfterMs ?? 0
        if retryAfter > 0 { serverNotBefore = max(serverNotBefore, platform.now() + retryAfter) }
        cancelRetry?()
        cancelRetry = platform.schedule(after: max(jittered, retryAfter)) { [self] in
            cancelRetry = nil
            refresh(nil, manual: false)
        }
    }

    // Runs off the state queue. Nil means the server confirmed nothing changed.
    private func fetch(user: User, knownVersion: Int?) throws -> Snapshot? {
        if let keyProblem { throw FlagbearerError(keyProblem) }
        var body: [String: Any] = ["clientKey": options.clientKey, "includeDetails": true]
        if let knownVersion { body["knownVersion"] = knownVersion }
        if let key = user.key { body["userKey"] = key }
        if let email = user.email { body["userEmail"] = email }
        if let anonymousId = loadAnonymousId() { body["anonymousId"] = anonymousId }

        let response: HTTPResponse
        do {
            response = try platform.post(
                url: "\(origin)/v1/evaluate", headers: [:],
                body: JSONSerialization.data(withJSONObject: body), timeoutMs: requestTimeoutMs
            )
        } catch {
            throw FlagbearerError("Flagbearer couldn't be reached: \(error.localizedDescription)", retryable: true)
        }
        if response.status == 204 { return nil }
        guard (200...299).contains(response.status) else {
            // The server's own explanation of a refused request, when it sent one.
            let json = (try? JSONSerialization.jsonObject(with: response.body)) as? [String: Any]
            let reason = (json?["message"] as? String).flatMap { $0.isEmpty ? nil : String($0.prefix(500)) }
            let seconds = response.retryAfter.flatMap(Double.init).flatMap { $0 >= 0 ? $0 : nil } ?? 0
            throw FlagbearerError(
                "Flagbearer request failed: \(response.status)\(reason.map { ". \($0)" } ?? "")",
                retryable: response.status == 408 || response.status == 429 || response.status >= 500,
                retryAfterMs: Int64(seconds * 1000)
            )
        }
        guard let snapshot = parseSnapshot(String(decoding: response.body, as: UTF8.self)) else {
            throw FlagbearerError("Invalid flag evaluation response")
        }
        return snapshot
    }

    // A random ID kept on this device, so a signed-out person counts once
    // however often they open the app. It's never used for targeting. Without
    // storage there's no ID, and the server falls back to the device's address.
    private func loadAnonymousId() -> String? {
        lock.lock()
        defer { lock.unlock() }
        if anonymousIdLoaded { return anonymousId }
        anonymousIdLoaded = true
        guard let storage = platform.storage else { return nil }
        // The server rejects a malformed ID, which would block flags, so replace it.
        if let saved = storage.get(anonymousIdKey), saved.range(of: uuidPattern, options: .regularExpression) != nil {
            anonymousId = saved
        } else {
            anonymousId = platform.randomId()
            storage.set(anonymousIdKey, anonymousId!)
        }
        return anonymousId
    }

    private func cacheUser(_ user: User) -> String {
        let pair: [Any] = [user.key ?? NSNull(), user.email ?? NSNull()]
        return (try? JSONSerialization.data(withJSONObject: pair)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    }

    private func readCache(_ user: User) -> Snapshot? {
        guard
            let saved = platform.storage?.get(cacheKey),
            let cached = (try? JSONSerialization.jsonObject(with: Data(saved.utf8))) as? [String: Any],
            cached["clientKey"] as? String == options.clientKey,
            cached["user"] as? String == cacheUser(user),
            let savedAt = (cached["savedAt"] as? NSNumber)?.int64Value,
            platform.now() - savedAt <= maxCacheAgeMs,
            let snapshot = cached["snapshot"] as? String
        else { return nil }
        return parseSnapshot(snapshot)
    }

    private func writeCache(_ user: User, _ snapshot: Snapshot) {
        guard options.cacheFlags, let storage = platform.storage else { return }
        let saved: [String: Any] = [
            "clientKey": options.clientKey,
            "user": cacheUser(user),
            "savedAt": platform.now(),
            "snapshot": snapshot.json,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: saved) else { return }
        platform.io { storage.set(cacheKey, String(decoding: data, as: UTF8.self)) }
    }

    private func debug(_ message: String) {
        if options.debug { platform.debug(message) }
    }
}
