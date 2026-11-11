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
/// the foreground they're rechecked about every 30 seconds.
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

    func debug(_ message: String) { NSLog("[Flagbearer] %@", message) }
    func warn(_ message: String) { NSLog("[Flagbearer] Warning: %@", message) }
    func error(_ message: String) { NSLog("[Flagbearer] Error: %@", message) }
}
