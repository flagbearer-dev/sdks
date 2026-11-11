import Foundation

#if canImport(UIKit) && !os(watchOS)
import UIKit
#endif

/// The SDK's version, sent with flag-check reports.
let flagbearerSDKVersion = "1.0.0"

/// Feature flags for an iOS app. Create one for the whole app, with your
/// environment's client key (it starts with `fbc_` and is safe to ship):
///
/// ```swift
/// let flags = Flagbearer(clientKey: "fbc_...")
///
/// if flags.isEnabled("new-checkout") { ... }
/// ```
///
/// Flags load in the background and are kept on the device, so the next launch
/// starts with the flags the user last had, even offline. While the app is in
/// the foreground, a change made in the dashboard arrives within a couple of
/// seconds over a live connection; without one, flags are rechecked about
/// every 30 seconds.
public final class Flagbearer: @unchecked Sendable {
    private let client: Client
    private var observers: [NSObjectProtocol] = []

    /// - Parameters:
    ///   - clientKey: your environment's client key.
    ///   - url: the Flagbearer SDK address. Only self-hosted setups change it.
    ///   - user: who flags are evaluated for. Call `identify` when it changes.
    ///   - debug: logs each fetch, and warns about flags that don't exist even
    ///     in release builds.
    ///   - appVersion: shown beside flag checks in the dashboard. Defaults to
    ///     the app's version.
    ///   - cacheFlags: start with the flags this user last had, until fresh
    ///     ones arrive.
    public init(
        clientKey: String,
        url: String = "https://sdk.flagbearer.dev",
        user: User = User(),
        debug: Bool = false,
        appVersion: String? = nil,
        cacheFlags: Bool = true
    ) {
        let version = appVersion
            ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let options = Options(
            clientKey: clientKey, url: url, user: user, debug: debug,
            appVersion: version, cacheFlags: cacheFlags
        )
        client = Client(options: options, platform: ApplePlatform())
        watchForeground()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    /// Whether the current user's flags have arrived.
    public var status: FlagsStatus { client.status }

    /// Whether a flag is on for the current user. Never blocks and never
    /// throws: until flags arrive, or for a flag that doesn't exist, it
    /// returns `defaultValue`.
    public func isEnabled(_ flag: String, default defaultValue: Bool = false) -> Bool {
        client.isEnabled(flag, default: defaultValue)
    }

    /// Every flag's current value, for error reports. Doesn't count as checking them.
    public func allFlags() -> [String: Bool] {
        client.allFlags()
    }

    /// Switches to another user, such as after sign-in or sign-out, and waits
    /// for their flags. Until they arrive, `isEnabled` returns that user's
    /// cached flags, or defaults. Throws `FlagbearerError` if they can't be
    /// fetched.
    public func identify(_ user: User) async throws {
        try await wait { self.client.identify(user, $0) }
    }

    /// Fetches the current user's flags now and waits for them.
    public func sync() async throws {
        try await wait { self.client.sync($0) }
    }

    /// Uploads pending flag-check reports now. They're also sent every 30 seconds.
    public func flush() async throws {
        try await wait { self.client.flush($0) }
    }

    /// Calls `listener` on the main queue whenever flags or `status` change.
    /// Call the returned function to stop.
    @discardableResult
    public func subscribe(_ listener: @escaping () -> Void) -> () -> Void {
        client.subscribe(listener)
    }

    /// Stops updates and uploads what's pending. The last flags stay readable.
    public func close() {
        client.close()
    }

    private func wait(_ start: @escaping (@escaping (Error?) -> Void) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            start { failure in
                if let failure { continuation.resume(throwing: failure) } else { continuation.resume() }
            }
        }
    }

    private func watchForeground() {
        #if canImport(UIKit) && !os(watchOS)
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) {
                [weak self] _ in self?.client.resume()
            },
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) {
                [weak self] _ in self?.client.background()
            },
        ]
        #endif
    }
}

// Reads the server's stream of flag changes, a line at a time, until it
// ends, fails, goes silent or is closed.
private final class EventStream: NSObject, URLSessionDataDelegate {
    private let events: StreamEvents
    private let lock = NSLock()
    private var session: URLSession?
    private var closed = false
    private var status: Int?
    private var retryAfter: String?
    private var buffer = Data()

    init(events: StreamEvents) {
        self.events = events
    }

    func start(_ url: URL) {
        let configuration = URLSessionConfiguration.ephemeral
        // The server pings every 15 seconds, so this is three missed pings.
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 24 * 3600
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        session.dataTask(with: request).resume()
    }

    func close() {
        lock.lock()
        closed = true
        lock.unlock()
        session?.invalidateAndCancel()
    }

    private var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 200 {
            if !isClosed { events.open() }
            completionHandler(.allow)
        } else {
            status = code
            retryAfter = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Retry-After")
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        buffer.append(data)
        while let end = buffer.firstIndex(of: 0x0A) {
            var line = buffer[buffer.startIndex..<end]
            if line.last == 0x0D { line = line.dropLast() }
            let text = String(decoding: line, as: UTF8.self)
            buffer.removeSubrange(buffer.startIndex...end)
            if !isClosed { events.line(text) }
        }
        // A stream with no line endings isn't ours; don't let it grow.
        if buffer.count > 100_000 { session.invalidateAndCancel() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        session.finishTasksAndInvalidate()
        if !isClosed { events.closed(status, retryAfter) }
    }
}

private struct DefaultsStorage: Storage {
    // Its own suite, so the app's own defaults aren't touched.
    let defaults = UserDefaults(suiteName: "dev.flagbearer") ?? .standard

    func get(_ key: String) -> String? { defaults.string(forKey: key) }
    func set(_ key: String, _ value: String) { defaults.set(value, forKey: key) }
}

private final class ApplePlatform: Platform {
    private let state = DispatchQueue(label: "dev.flagbearer.state")
    private let network = DispatchQueue(label: "dev.flagbearer.io")
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration)
    }()

    let sdkVersion = flagbearerSDKVersion
    let storage: Storage? = DefaultsStorage()

    var development: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    func random() -> Double { Double.random(in: 0..<1) }
    func randomId() -> String { UUID().uuidString.lowercased() }

    func run(_ task: @escaping () -> Void) { state.async(execute: task) }

    func schedule(after ms: Int64, _ task: @escaping () -> Void) -> () -> Void {
        let work = DispatchWorkItem(block: task)
        state.asyncAfter(deadline: .now() + .milliseconds(Int(ms)), execute: work)
        return { work.cancel() }
    }

    func io(_ task: @escaping () -> Void) { network.async(execute: task) }
    func notify(_ task: @escaping () -> Void) { DispatchQueue.main.async(execute: task) }

    func post(url: String, headers: [String: String], body: Data, timeoutMs: Int) throws -> HTTPResponse {
        guard let address = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: address, timeoutInterval: Double(timeoutMs) / 1000)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }

        // The I/O queue exists to wait, so blocking here is fine.
        var result: Result<HTTPResponse, Error> = .failure(URLError(.unknown))
        let finished = DispatchSemaphore(value: 0)
        session.dataTask(with: request) { data, response, error in
            if let response = response as? HTTPURLResponse {
                result = .success(
                    HTTPResponse(
                        status: response.statusCode, body: data ?? Data(),
                        retryAfter: response.value(forHTTPHeaderField: "Retry-After")
                    )
                )
            } else {
                result = .failure(error ?? URLError(.badServerResponse))
            }
            finished.signal()
        }.resume()
        finished.wait()
        return try result.get()
    }

    func openStream(url: String, events: StreamEvents) -> () -> Void {
        guard let address = URL(string: url) else {
            events.closed(nil, nil)
            return {}
        }
        let stream = EventStream(events: events)
        stream.start(address)
        return stream.close
    }

    func debug(_ message: String) { NSLog("[Flagbearer] %@", message) }
    func warn(_ message: String) { NSLog("[Flagbearer] Warning: %@", message) }
    func error(_ message: String) { NSLog("[Flagbearer] Error: %@", message) }
}
