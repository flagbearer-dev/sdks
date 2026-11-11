# flagbearer for Android

The Flagbearer SDK for Android apps, written in Kotlin. It fetches the current
user's flags with a public client key, keeps them on the device so the next
launch works offline, and rechecks them while the app is in the foreground.
Requires Android 6.0 (API 23) or later and has no dependencies.

Server-side Kotlin and Java use [`dev.flagbearer:flagbearer`](../java) instead,
which needs a secret server key and must never ship in an app.

## Docs

Setup, users, checking flags and listening for changes are covered at
[docs.flagbearer.dev/sdks/android](https://docs.flagbearer.dev/sdks/android).

## Development

Requires a JDK 17 or later and the Android SDK (`ANDROID_HOME`, or `sdk.dir`
in `local.properties`). From this directory:

```sh
./gradlew testDebugUnitTest
```

The client's logic is in `Client.kt` and has no Android in it: `Platform.kt`
describes what it needs from the device, and the tests stand in for that with a
fake clock, network and storage. `Flagbearer.kt` is the public class and the
real Android implementation.

## Releasing

The artifact is `dev.flagbearer:flagbearer-android` on Maven Central. Bump
`version` in `build.gradle.kts`, merge, then run the **Publish Android**
workflow. Maven Central versions can't be reused.
