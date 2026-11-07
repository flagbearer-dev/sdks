# Flagbearer for .NET

.NET SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Targets .NET 8 and .NET 10, and has no dependencies outside the framework.

```sh
dotnet add package Flagbearer
```

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/dotnet](https://docs.flagbearer.dev/sdks/dotnet).

## Development

Requires the .NET 10 SDK. From this directory:

```sh
dotnet test Flagbearer.sln
dotnet format Flagbearer.sln --verify-no-changes
```

The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which the other SDKs also run. Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/dotnet-server`](../testing/servers/dotnet-server).

## Releasing

Bump `<Version>` in `src/Flagbearer/Flagbearer.csproj`; the SDK reports that
version with its exposures. Then build the package and push it to NuGet:

```sh
dotnet pack src/Flagbearer -c Release -o dist
dotnet nuget push dist/Flagbearer.<version>.nupkg --source https://api.nuget.org/v3/index.json --api-key <key>
```

NuGet versions can't be reused, so a version that's already published is
rejected; bump it to release.
