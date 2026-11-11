# Flagbearer for iOS

The Flagbearer SDK for iOS and other Apple platforms, written in Swift. It
fetches the current user's flags with a public client key, keeps them on the
device so the next launch works offline, and picks up changes within seconds
while the app is in the foreground. Requires iOS 15, macOS 12, tvOS 15 or watchOS 8, and has no
dependencies.

## Docs

Setup, users, checking flags and listening for changes are covered at
[docs.flagbearer.dev/sdks/ios](https://docs.flagbearer.dev/sdks/ios).

## Development

Swift Package Manager reads a package only from the root of a repository, so
[`Package.swift`](../Package.swift) is at the repository's root and points
here. From the root:

```sh
swift test
```

The client's logic is in `Client.swift` and has no UIKit in it:
`Platform.swift` describes what it needs from the device, and the tests stand
in for that with a fake clock, network and storage. `Flagbearer.swift` is the
public class and the real implementation.

## Releasing

Apps install the package from this repository by version tag. Bump
`flagbearerSDKVersion` in `Sources/Flagbearer/Flagbearer.swift`, merge, then
run the **Release Swift** workflow, which tags the commit `X.Y.Z`. The tag has
no prefix, because Swift Package Manager only reads plain version tags.
