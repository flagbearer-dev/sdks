# flagbearer for Java and Kotlin

Java SDK for Flagbearer, usable from Kotlin and other JVM languages. Downloads an
environment's ruleset and evaluates flags locally for each user, without making
a network request for every flag check. Requires Java 11 or later and has no
runtime dependencies.

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/java](https://docs.flagbearer.dev/sdks/java).

## Development

Requires a JDK 17 or later to build; the library compiles for Java 11. From this
directory:

```sh
./gradlew test
```

The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which the other server SDKs also run. Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/java-server/Fixture.java`](../testing/servers/java-server/Fixture.java),
against the jar that `./gradlew fixtureJar` writes to `build/fixture/flagbearer.jar`.

## Releasing

The artifact is `dev.flagbearer:flagbearer` on Maven Central. Publishing isn't
wired up yet. It needs:

1. A Central Portal account with the `dev.flagbearer` namespace verified, which
   proves ownership of the `flagbearer.dev` domain with a DNS record.
2. A GPG key to sign the artifacts, stored as CI secrets with the Portal token.
3. The `maven-publish` and `signing` plugins in `build.gradle.kts`, with the POM
   name, description, license, developer and SCM fields Central requires.

Then bump `version` in `build.gradle.kts`, merge, and publish the jar, sources
jar and javadoc jar from CI. Maven Central versions can't be reused.
