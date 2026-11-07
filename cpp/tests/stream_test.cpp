#include <doctest/doctest.h>

#include "internal.hpp"

using namespace flagbearer::detail;

namespace {

struct Parsed {
  std::vector<int64_t> versions;
  bool ok = true;
};

Parsed parse(std::initializer_list<std::string> pieces) {
  Parsed result;
  EventParser parser([&](int64_t version) { result.versions.push_back(version); });
  for (const auto& piece : pieces) {
    if (!parser.feed(piece)) {
      result.ok = false;
      break;
    }
  }
  return result;
}

}  // namespace

TEST_CASE("reads version events and ignores others") {
  auto got = parse({"retry: 9000\n\n", ": comment\n", "event: version\ndata: {\"version\":4}\n\n",
                    "event: ping\ndata: {}\n\n", "data: {\"version\":99}\n\n"});
  CHECK(got.versions == std::vector<int64_t>{4});
}

TEST_CASE("any line ending, split anywhere") {
  for (std::string ending : {"\n", "\r\n", "\r"}) {
    std::string text = "event: version" + ending + "data: {\"version\":7}" + ending + ending;
    for (size_t cut = 0; cut <= text.size(); ++cut) {
      CAPTURE(cut);
      // A trailing CR might be half a CRLF, so it's read once the next byte arrives.
      auto got = parse({text.substr(0, cut), text.substr(cut), ": heartbeat\n"});
      CHECK(got.versions == std::vector<int64_t>{7});
    }
  }
}

TEST_CASE("multi-byte characters split across reads") {
  std::string text = "event: version\ndata: {\"version\":5,\"note\":\"\xc3\xbc\xf0\x9f\x98\x80\"}\n\n";
  for (size_t cut = 0; cut <= text.size(); ++cut) {
    CAPTURE(cut);
    CHECK(parse({text.substr(0, cut), text.substr(cut)}).versions == std::vector<int64_t>{5});
  }
}

TEST_CASE("multi-line data joins with newlines") {
  CHECK(parse({"event: version\ndata: {\"version\":\ndata: 5}\n\n"}).versions == std::vector<int64_t>{5});
}

TEST_CASE("malformed versions are ignored") {
  for (std::string data : {"nope", R"({"version":-1})", R"({"version":true})", R"({"version":1.5})", "[]"}) {
    CAPTURE(data);
    auto got = parse({"event: version\ndata: " + data + "\n\n"});
    CHECK(got.versions.empty());
    CHECK(got.ok);
  }
}

TEST_CASE("oversized events fail the connection") {
  CHECK_FALSE(parse({"data: " + std::string(kMaxEventLength, 'x') + "\n"}).ok);
  CHECK_FALSE(parse({std::string(kMaxEventLength + 1, 'x')}).ok);
}
