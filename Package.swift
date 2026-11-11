// swift-tools-version:5.9
import PackageDescription

// The Flagbearer SDK for iOS and other Apple platforms. Swift Package Manager
// reads a package only from the root of a repository, so the manifest is here
// and the code is in swift/.
let package = Package(
    name: "Flagbearer",
    platforms: [.iOS(.v15), .macOS(.v12), .tvOS(.v15), .watchOS(.v8)],
    products: [.library(name: "Flagbearer", targets: ["Flagbearer"])],
    targets: [
        .target(name: "Flagbearer", path: "swift/Sources/Flagbearer"),
        .testTarget(
            name: "FlagbearerTests",
            dependencies: ["Flagbearer"],
            path: "swift/Tests/FlagbearerTests"
        ),
    ],
    swiftLanguageVersions: [.v5]
)
