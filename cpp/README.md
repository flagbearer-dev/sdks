# flagbearer for C++

C++ SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Requires C++17, CMake 3.16 or later, and libcurl 7.68 or later; it has no other
dependencies.

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/cpp](https://docs.flagbearer.dev/sdks/cpp).

## Development

From this directory:

```sh
cmake -S . -B build -DFLAGBEARER_WARNINGS_AS_ERRORS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

Sanitizer builds take `-DFLAGBEARER_SANITIZE=address,undefined` or
`-DFLAGBEARER_SANITIZE=thread`. The tests fetch the doctest header, pinned by
hash, on first configure, and include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which every SDK runs. Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/cpp-server`](../testing/servers/cpp-server).

## Releasing

C++ has no central registry, so a release is a git tag that projects fetch with
CMake:

```cmake
include(FetchContent)
FetchContent_Declare(flagbearer
  GIT_REPOSITORY https://github.com/<public repo>.git
  GIT_TAG sdk-cpp/v0.1.0
  SOURCE_SUBDIR cpp)
FetchContent_MakeAvailable(flagbearer)
```

Bump `kVersion` in `include/flagbearer/flagbearer.hpp` and `project(VERSION)`
in `CMakeLists.txt` together, merge, then tag. Like Go, this needs the source
in a public repository. vcpkg and Conan ports can follow once the API settles;
both build from the same tag.
