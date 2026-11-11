import XCTest

@testable import Flagbearer

private struct Offline: Error {}

// Stands in for the device: time moves only when a test advances it, work
// runs at once on the calling thread, and the test says how the server answers.
private final class FakeDevice: Platform, Storage {
    struct Request {
        var path: String
        var headers: [String: String]
        var body: [String: Any]
    }

    private final class Timer {
        let at: Int64
        let task: () -> Void
        var cancelled = false

        init(at: Int64, task: @escaping () -> Void) {
            self.at = at
            self.task = task
        }
    }

    var time: Int64 = 1_800_000_000_000
    private var timers: [Timer] = []
    private var nextId = 0
    var requests: [Request] = []
    var warnings: [String] = []
    var errors: [String] = []
    var saved: [String: String] = [:]
    // How /v1/evaluate answers. Uploads to /v1/exposures are always accepted.
    var evaluate: ([String: Any]) throws -> HTTPResponse = { _ in flags(1) }

    let sdkVersion = "0.0.0-test"
    let development: Bool
    var storage: Storage? { self }

    init(development: Bool = true) {
        self.development = development
    }

    func get(_ key: String) -> String? { saved[key] }
    func set(_ key: String, _ value: String) { saved[key] = value }

    func now() -> Int64 { time }
    func random() -> Double { 0.5 }
    func randomId() -> String {
        nextId += 1
        return String(format: "00000000-0000-4000-8000-%012d", nextId)
    }

    func run(_ task: @escaping () -> Void) { task() }
    func io(_ task: @escaping () -> Void) { task() }
    func notify(_ task: @escaping () -> Void) { task() }

    func schedule(after ms: Int64, _ task: @escaping () -> Void) -> () -> Void {
        let timer = Timer(at: time + ms, task: task)
        timers.append(timer)
        return { timer.cancelled = true }
    }

    func advance(_ ms: Int64) {
        let until = time + ms
        while let next = timers.filter({ !$0.cancelled && $0.at <= until }).min(by: { $0.at < $1.at }) {
            timers.removeAll { $0 === next }
            time = next.at
            next.task()
        }
        time = until
    }

    func post(url: String, headers: [String: String], body: Data, timeoutMs: Int) throws -> HTTPResponse {
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
        let request = Request(path: url.replacingOccurrences(of: "http://sdk.test", with: ""), headers: headers, body: json)
        requests.append(request)
        if request.path == "/v1/exposures" {
            let events = (json["events"] as? [Any])?.count ?? 0
            return HTTPResponse(status: 200, body: Data("{\"accepted\":\(events),\"rejectedChecks\":0}".utf8))
        }
        return try evaluate(json)
    }

    // Streams the client opened, newest last. A test plays the server's part
    // on one; until it does, the stream is still connecting.
    var streams: [StreamEvents] = []
    var closedStreams = 0

    func openStream(url: String, events: StreamEvents) -> () -> Void {
        XCTAssertEqual(url, "http://sdk.test/v1/stream?clientKey=fbc_test")
        streams.append(events)
        return { self.closedStreams += 1 }
    }

    /// The server announces a new version on the newest stream.
    func announce(_ version: Int) {
        let stream = streams[streams.count - 1]
        stream.line("event: version")
        stream.line("data: {\"version\":\(version)}")
        stream.line("")
    }

    func debug(_ message: String) {}
    func warn(_ message: String) { warnings.append(message) }
    func error(_ message: String) { errors.append(message) }

    var fetches: [Request] { requests.filter { $0.path == "/v1/evaluate" } }
    var uploads: [Request] { requests.filter { $0.path == "/v1/exposures" } }
}

// A server answer with every flag given, as the real server sends them.
private func flags(_ version: Int, _ values: [String: Bool] = [:]) -> HTTPResponse {
    let details = values.mapValues { value -> [String: Any] in
        ["value": value, "reason": value ? "rollout" : "off", "configurationVersion": version]
    }
    let body: [String: Any] = ["version": version, "flags": values, "details": details]
    return HTTPResponse(status: 200, body: try! JSONSerialization.data(withJSONObject: body))
}

private func refused(_ status: Int, _ message: String) -> HTTPResponse {
    let body = ["code": "INVALID_CREDENTIALS", "message": message]
    return HTTPResponse(status: status, body: try! JSONSerialization.data(withJSONObject: body))
}

private let andrew = User(key: "1", email: "andrew@example.com")
private let bea = User(key: "2", email: "bea@example.com")

private func start(
    _ device: FakeDevice, user: User = andrew, clientKey: String = "fbc_test",
    url: String = "http://sdk.test", debug: Bool = false
) -> Client {
    let options = Options(
        clientKey: clientKey, url: url, user: user, debug: debug, appVersion: "1.2.3", cacheFlags: true
    )
    return Client(options: options, platform: device)
}

final class ClientTests: XCTestCase {
    func testFlagsLoadForTheUserAndAMissingFlagReturnsTheDefaultGiven() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": true, "dark-mode": false]) }

        let client = start(device)

        XCTAssertEqual(client.status, .ready)
        XCTAssertTrue(client.isEnabled("new-checkout", default: false))
        XCTAssertFalse(client.isEnabled("dark-mode", default: true))
        XCTAssertTrue(client.isEnabled("not-a-flag", default: true))
        let sent = device.fetches[0].body
        XCTAssertEqual(device.fetches.count, 1)
        XCTAssertEqual(sent["clientKey"] as? String, "fbc_test")
        XCTAssertEqual(sent["userKey"] as? String, "1")
        XCTAssertEqual(sent["userEmail"] as? String, "andrew@example.com")
        XCTAssertEqual(sent["anonymousId"] as? String, device.saved["flagbearer:anon"])
        XCTAssertNotNil(device.saved["flagbearer:anon"])
    }

    func testAnOfflineLaunchStartsWithTheFlagsThisUserLastHadAndNobodyElses() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": true]) }
        start(device).close()
        device.evaluate = { _ in throw Offline() }

        let sameUser = start(device)
        let otherUser = start(device, user: bea)

        XCTAssertEqual(sameUser.status, .ready)
        XCTAssertTrue(sameUser.isEnabled("new-checkout", default: false))
        XCTAssertEqual(otherUser.status, .failed)
        XCTAssertFalse(otherUser.isEnabled("new-checkout", default: false))
        // Being offline isn't reported as an error.
        XCTAssertEqual(device.errors, [])
    }

    func testCachedFlagsOlderThanSevenDaysAreIgnored() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": true]) }
        start(device).close()
        device.evaluate = { _ in throw Offline() }
        device.time += 8 * 86_400_000

        let client = start(device)

        XCTAssertEqual(client.status, .failed)
        XCTAssertFalse(client.isEnabled("new-checkout", default: false))
    }

    func testAServerKeyIsNeverSentAndTheLogSaysToUseAClientKey() {
        let device = FakeDevice()

        let client = start(device, clientKey: "fbs_secret")

        XCTAssertEqual(device.fetches.count, 0)
        XCTAssertEqual(client.status, .failed)
        XCTAssertFalse(client.isEnabled("new-checkout", default: false))
        XCTAssertEqual(device.errors.count, 1)
        XCTAssertTrue(device.errors[0].contains("This is a server key (fbs_)"))
    }

    func testAnAddressThatIsNotHTTPIsExplainedAndNeverRequested() {
        let device = FakeDevice()

        let client = start(device, url: "sdk.flagbearer.dev")

        XCTAssertEqual(device.requests.count, 0)
        XCTAssertEqual(client.status, .failed)
        XCTAssertTrue(device.errors[0].contains("must start with http:// or https://"))
    }

    func testARefusedKeyIsExplainedOnceWithTheServersReasonAndNothingRetries() {
        let device = FakeDevice()
        device.evaluate = { _ in refused(401, "This SDK key isn't recognized.") }

        let client = start(device)
        device.advance(10 * 60_000)
        var failure: Error?
        client.sync { failure = $0 }

        XCTAssertEqual(client.status, .failed)
        XCTAssertEqual(
            device.errors.first,
            "Flags have stopped updating and keep their current or default values until this is fixed. "
                + "Flagbearer request failed: 401. This SDK key isn't recognized."
        )
        // One request at launch, none from polling, one for the sync() asked for.
        XCTAssertEqual(device.fetches.count, 2)
        XCTAssertEqual(
            (failure as? FlagbearerError)?.message,
            "Flagbearer request failed: 401. This SDK key isn't recognized."
        )
    }

    func testCheckingAFlagThatDoesNotExistWarnsOnceInADebugBuildAndAnExistingFlagNeverDoes() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": true]) }
        let client = start(device)

        _ = client.isEnabled("new-checkout", default: false)
        _ = client.isEnabled("new-checkotu", default: false)
        _ = client.isEnabled("new-checkotu", default: false)

        XCTAssertEqual(device.warnings.count, 1)
        XCTAssertTrue(device.warnings[0].hasPrefix("No flag \"new-checkotu\" exists"))
    }

    func testInAReleaseBuildAFlagThatDoesNotExistWarnsNobodyUnlessDebugIsOn() {
        let release = FakeDevice(development: false)
        _ = start(release).isEnabled("new-checkotu", default: false)
        let debugging = FakeDevice(development: false)
        _ = start(debugging, debug: true).isEnabled("new-checkotu", default: false)

        XCTAssertEqual(release.warnings, [])
        XCTAssertEqual(debugging.warnings.count, 1)
    }

    func testFlagsAreRecheckedEvery30SecondsAndAListenerHearsOnlyRealChanges() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": false]) }
        let client = start(device)
        var changes = 0
        _ = client.subscribe { changes += 1 }

        // Nothing changed: the server answers 204 to the version the client has.
        var knownVersion: Int?
        device.evaluate = { body in
            knownVersion = body["knownVersion"] as? Int
            return HTTPResponse(status: 204, body: Data())
        }
        device.advance(31_000)
        let afterUnchanged = changes
        device.evaluate = { _ in flags(2, ["new-checkout": true]) }
        device.advance(31_000)

        XCTAssertEqual(knownVersion, 1)
        XCTAssertEqual(device.fetches.count, 3)
        XCTAssertEqual(afterUnchanged, 0)
        XCTAssertEqual(changes, 1)
        XCTAssertTrue(client.isEnabled("new-checkout", default: false))
    }

    func testAFailedFetchRetriesWithBackoffAndKeepsTheFlagsItHad() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": true]) }
        let client = start(device)
        device.evaluate = { _ in HTTPResponse(status: 503, body: Data()) }

        // The poll at 30 seconds fails; the first retry comes a second later.
        device.advance(30_500)
        let afterFailure = device.fetches.count
        device.advance(1_000)

        XCTAssertEqual(afterFailure, 2)
        XCTAssertEqual(device.fetches.count, 3)
        XCTAssertEqual(client.status, .ready)
        XCTAssertTrue(client.isEnabled("new-checkout", default: false))
        XCTAssertEqual(device.errors, [])
    }

    func testAServersRetryAfterIsWaitedOut() {
        let device = FakeDevice()
        let client = start(device)
        device.evaluate = { _ in HTTPResponse(status: 429, body: Data(), retryAfter: "120") }
        device.advance(31_000)
        let before = device.fetches.count

        device.advance(110_000)
        client.resume()
        let during = device.fetches.count
        device.advance(15_000)

        XCTAssertEqual(before, during)
        XCTAssertEqual(device.fetches.count, before + 1)
    }

    func testFiveMinutesInTheBackgroundStopsPollingAndComingBackRechecksAtOnce() {
        let device = FakeDevice()
        let client = start(device)

        client.background()
        device.advance(5 * 60_000)
        let whenPaused = device.fetches.count
        device.advance(10 * 60_000)
        let stillPaused = device.fetches.count
        client.resume()

        XCTAssertEqual(whenPaused, stillPaused)
        XCTAssertEqual(device.fetches.count, whenPaused + 1)
    }

    func testIdentifySwitchesToTheNewUsersFlagsAndDropsThePreviousUsers() {
        let device = FakeDevice()
        device.evaluate = { body in
            body["userKey"] as? String == "1" ? flags(4, ["new-checkout": true]) : flags(4, ["dark-mode": true])
        }
        let client = start(device)
        var failure: Error? = Offline()

        client.identify(bea) { failure = $0 }

        XCTAssertNil(failure)
        XCTAssertFalse(client.isEnabled("new-checkout", default: false))
        XCTAssertTrue(client.isEnabled("dark-mode", default: false))
        // The new user's first fetch asks for everything, not for changes since version 4.
        XCTAssertNil(device.fetches.last?.body["knownVersion"])
        XCTAssertEqual(client.allFlags(), ["dark-mode": true])
    }

    func testFlagChecksUploadOncePerWindowWithTheUserTheCountsAndTheSDKsName() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(7, ["new-checkout": true]) }
        let client = start(device)

        for _ in 0..<3 { _ = client.isEnabled("new-checkout", default: false) }
        _ = client.isEnabled("not-a-flag", default: false)
        _ = client.allFlags()
        var failure: Error? = Offline()
        client.flush { failure = $0 }

        XCTAssertNil(failure)
        XCTAssertEqual(device.uploads.count, 1)
        let upload = device.uploads[0]
        XCTAssertEqual(upload.headers["Authorization"], "Bearer fbc_test")
        XCTAssertEqual(upload.body["sdk"] as? String, "ios")
        XCTAssertEqual(upload.body["appVersion"] as? String, "1.2.3")
        XCTAssertEqual((upload.body["user"] as? [String: Any])?["key"] as? String, "1")
        let events = upload.body["events"] as? [[String: Any]] ?? []
        let byFlag = Dictionary(uniqueKeysWithValues: events.map { ($0["flagKey"] as? String ?? "", $0) })
        XCTAssertEqual(Set(byFlag.keys), ["new-checkout", "not-a-flag"])
        XCTAssertEqual(byFlag["new-checkout"]?["checkCount"] as? Int, 3)
        XCTAssertEqual(byFlag["new-checkout"]?["reason"] as? String, "rollout")
        XCTAssertEqual(byFlag["not-a-flag"]?["reason"] as? String, "fallback")
    }

    func testWithAStreamOpenAChangeTheServerAnnouncesArrivesWithinTwoSecondsWithoutPolling() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1, ["new-checkout": false]) }
        let client = start(device)
        device.streams[0].open()
        // The server opens every stream by announcing the current version.
        device.announce(1)

        device.advance(5 * 60_000)
        let whileQuiet = device.fetches.count
        device.evaluate = { _ in flags(2, ["new-checkout": true]) }
        device.announce(2)
        device.advance(2_000)

        // No polling while the stream is open, then one fetch for the change.
        XCTAssertEqual(whileQuiet, 1)
        XCTAssertEqual(device.fetches.count, 2)
        XCTAssertTrue(client.isEnabled("new-checkout", default: false))
    }

    func testWhenTheStreamEndsPollingTakesOverAndTheStreamIsReopened() {
        let device = FakeDevice()
        _ = start(device)
        device.streams[0].open()
        device.announce(1)
        device.advance(2 * 60_000)
        let whileStreaming = device.fetches.count

        device.streams[0].closed(nil, nil)
        device.advance(9_000)
        let beforeReconnect = device.streams.count
        device.advance(31_000)
        // Changes made while there was no stream weren't announced, so
        // reopening it rechecks the flags.
        let beforeReopen = device.fetches.count
        device.streams[device.streams.count - 1].open()

        XCTAssertEqual(whileStreaming, 1)
        XCTAssertEqual(beforeReconnect, 1)
        XCTAssertEqual(device.streams.count, 2)
        // One poll while there was no stream, then the catch-up on reopening.
        XCTAssertEqual(beforeReopen, 2)
        XCTAssertEqual(device.fetches.count, 3)
    }

    func testAStreamTheServerRefusesIsNotReopenedAndPollingCarriesOn() {
        let device = FakeDevice()
        _ = start(device)

        device.streams[0].closed(403, nil)
        device.advance(5 * 60_000)

        XCTAssertEqual(device.streams.count, 1)
        XCTAssertGreaterThan(device.fetches.count, 5)
    }

    func testAStreamTurnedAwayForBeingOverTheLimitWaitsOutRetryAfter() {
        let device = FakeDevice()
        _ = start(device)

        device.streams[0].closed(429, "60")
        device.advance(59_000)
        let before = device.streams.count
        device.advance(2_000)

        XCTAssertEqual(before, 1)
        XCTAssertEqual(device.streams.count, 2)
    }

    func testFiveMinutesInTheBackgroundClosesTheStreamAndComingBackReopensIt() {
        let device = FakeDevice()
        let client = start(device)
        device.streams[0].open()

        client.background()
        device.advance(4 * 60_000)
        let closedEarly = device.closedStreams
        device.advance(60_000)
        let closedLater = device.closedStreams
        client.resume()

        XCTAssertEqual(closedEarly, 0)
        XCTAssertEqual(closedLater, 1)
        XCTAssertEqual(device.streams.count, 2)
    }

    func testARefusedKeyLeavesNoStreamOpen() {
        let device = FakeDevice()
        device.evaluate = { _ in flags(1) }
        let client = start(device)
        device.streams[0].open()
        device.evaluate = { _ in refused(401, "This SDK key isn't recognized.") }

        client.sync { _ in }
        device.advance(5 * 60_000)

        XCTAssertEqual(device.streams.count, 1)
        XCTAssertEqual(device.closedStreams, 1)
    }

    func testAServerKeyOpensNoStream() {
        let device = FakeDevice()

        _ = start(device, clientKey: "fbs_secret")

        XCTAssertEqual(device.streams.count, 0)
    }
}
