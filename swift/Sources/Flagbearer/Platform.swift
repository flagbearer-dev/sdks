import Foundation

/// Who flags are evaluated for. Both fields are optional; a signed-out user has neither.
public struct User: Hashable, Sendable {
    public var key: String?
    public var email: String?

    public init(key: String? = nil, email: String? = nil) {
        self.key = key
        self.email = email
    }
}

/// - loading: no flags yet for the current user, so `isEnabled` returns defaults.
/// - ready: the current user's flags have arrived, from the server or the cache.
///   They stay ready through later failed refreshes, keeping the last flags.
/// - failed: fetching the current user's flags failed before any arrived.
public enum FlagsStatus: Sendable {
    case loading, ready, failed
}

/// A request Flagbearer couldn't complete, with the server's reason when it gave one.
public struct FlagbearerError: Error, LocalizedError, CustomStringConvertible, Sendable {
    public let message: String
    let retryable: Bool
    let retryAfterMs: Int64

    init(_ message: String, retryable: Bool = false, retryAfterMs: Int64 = 0) {
        self.message = message
        self.retryable = retryable
        self.retryAfterMs = retryAfterMs
    }

    public var errorDescription: String? { message }
    public var description: String { message }
}

struct HTTPResponse {
    var status: Int
    var body: Data
    var retryAfter: String?
}

/// What a live-update stream reports, from whichever thread reads it.
struct StreamEvents {
    /// The server accepted the stream.
    var open: () -> Void
    /// One line of the stream, without its line ending.
    var line: (String) -> Void
    /// The stream ended. The status is the server's refusal, or nil if it had
    /// opened or never connected.
    var closed: (_ status: Int?, _ retryAfter: String?) -> Void
}

/// Where the anonymous ID and the last flags are kept between launches.
protocol Storage {
    func get(_ key: String) -> String?
    func set(_ key: String, _ value: String)
}

/// Everything the client needs from the device, so tests can stand in for it:
/// queues, timers, the network, storage and the log.
protocol Platform: AnyObject {
    var sdkVersion: String { get }
    /// A debug build, where warnings about the app's own code are worth printing.
    var development: Bool { get }
    var storage: Storage? { get }

    /// Milliseconds since 1970.
    func now() -> Int64
    /// A number in [0, 1), for jitter.
    func random() -> Double
    func randomId() -> String

    /// Runs on the one queue that owns the client's state.
    func run(_ task: @escaping () -> Void)
    /// Runs `task` on the state queue after `ms`. Returns a cancel function.
    func schedule(after ms: Int64, _ task: @escaping () -> Void) -> () -> Void
    /// Runs blocking work (network, storage) off the state queue.
    func io(_ task: @escaping () -> Void)
    /// Calls a listener where the app expects it: the main queue.
    func notify(_ task: @escaping () -> Void)
    /// Blocks until the server answers. Throws when it can't be reached.
    func post(url: String, headers: [String: String], body: Data, timeoutMs: Int) throws -> HTTPResponse

    /// Opens the server's stream of flag changes and reads it until it ends or
    /// goes silent. Returns a function that closes it; after that, no more
    /// events arrive.
    func openStream(url: String, events: StreamEvents) -> () -> Void

    func debug(_ message: String)
    func warn(_ message: String)
    func error(_ message: String)
}

/// A flag's value and why it has it, as the server reports it.
struct Evaluation {
    var value: Bool
    var reason: String
    var configurationVersion: Int?
}
