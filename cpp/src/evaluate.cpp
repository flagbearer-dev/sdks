#include <cstring>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

// ---- SHA-256 (FIPS 180-4) -----------------------------------------------------

namespace {

constexpr std::array<uint32_t, 64> kRound = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void compress(std::array<uint32_t, 8>& h, const uint8_t* block) {
  uint32_t w[64];
  for (int i = 0; i < 16; ++i) {
    w[i] = (uint32_t{block[i * 4]} << 24) | (uint32_t{block[i * 4 + 1]} << 16) |
           (uint32_t{block[i * 4 + 2]} << 8) | uint32_t{block[i * 4 + 3]};
  }
  for (int i = 16; i < 64; ++i) {
    uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
    uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
  for (int i = 0; i < 64; ++i) {
    uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
    uint32_t ch = (e & f) ^ (~e & g);
    uint32_t t1 = k + s1 + ch + kRound[static_cast<size_t>(i)] + w[i];
    uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
    uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
    uint32_t t2 = s0 + maj;
    k = g;
    g = f;
    f = e;
    e = d + t1;
    d = c;
    c = b;
    b = a;
    a = t1 + t2;
  }
  h[0] += a;
  h[1] += b;
  h[2] += c;
  h[3] += d;
  h[4] += e;
  h[5] += f;
  h[6] += g;
  h[7] += k;
}

}  // namespace

std::array<uint8_t, 32> sha256(std::string_view data) {
  std::array<uint32_t, 8> h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                               0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
  const auto* bytes = reinterpret_cast<const uint8_t*>(data.data());
  size_t full = data.size() / 64 * 64;
  for (size_t i = 0; i < full; i += 64) compress(h, bytes + i);

  uint8_t tail[128] = {};
  size_t rest = data.size() - full;
  if (rest > 0) std::memcpy(tail, bytes + full, rest);
  tail[rest] = 0x80;
  size_t length = rest < 56 ? 64 : 128;
  uint64_t bits = static_cast<uint64_t>(data.size()) * 8;
  for (int i = 0; i < 8; ++i) tail[length - 1 - static_cast<size_t>(i)] = static_cast<uint8_t>(bits >> (8 * i));
  compress(h, tail);
  if (length == 128) compress(h, tail + 64);

  std::array<uint8_t, 32> digest{};
  for (size_t i = 0; i < 8; ++i) {
    digest[i * 4] = static_cast<uint8_t>(h[i] >> 24);
    digest[i * 4 + 1] = static_cast<uint8_t>(h[i] >> 16);
    digest[i * 4 + 2] = static_cast<uint8_t>(h[i] >> 8);
    digest[i * 4 + 3] = static_cast<uint8_t>(h[i]);
  }
  return digest;
}

// A user's place in a flag's rollout, 0-9999. Below rollout_bps means on.
int64_t bucket(std::string_view flag_key, std::string_view user_key) {
  std::string input;
  input.reserve(flag_key.size() + 1 + user_key.size());
  input.append(flag_key).append(":").append(user_key);
  // The digest read as a big-endian integer, mod 10000, one byte at a time.
  int64_t remainder = 0;
  for (uint8_t byte : sha256(input)) remainder = (remainder * 256 + byte) % 10000;
  return remainder;
}

// ---- UTF-8 ------------------------------------------------------------------

bool next_code_point(std::string_view text, size_t& pos, uint32_t& code) {
  auto at = [&](size_t i) { return static_cast<unsigned char>(text[i]); };
  unsigned char c = at(pos);
  size_t length = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
  if (length == 0 || pos + length > text.size()) {
    ++pos;
    code = 0xFFFD;
    return false;
  }
  uint32_t value = length == 1 ? c : length == 2 ? (c & 0x1F) : length == 3 ? (c & 0x0F) : (c & 0x07);
  for (size_t i = 1; i < length; ++i) {
    unsigned char next = at(pos + i);
    if ((next >> 6) != 0x2) {
      ++pos;
      code = 0xFFFD;
      return false;
    }
    value = (value << 6) | (next & 0x3F);
  }
  static constexpr uint32_t kMin[] = {0, 0, 0x80, 0x800, 0x10000};
  if (value < kMin[length] || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
    ++pos;
    code = 0xFFFD;
    return false;
  }
  pos += length;
  code = value;
  return true;
}

size_t js_length(std::string_view utf8) {
  size_t units = 0;
  for (size_t pos = 0; pos < utf8.size();) {
    uint32_t code = 0;
    next_code_point(utf8, pos, code);
    units += code >= 0x10000 ? 2 : 1;
  }
  return units;
}

namespace {

// The characters JavaScript's trim() removes.
bool is_js_space(uint32_t code) {
  switch (code) {
    case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D: case 0x20: case 0xA0:
    case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000: case 0xFEFF:
      return true;
    default:
      return code >= 0x2000 && code <= 0x200A;
  }
}

}  // namespace

std::string js_trim(std::string_view text) {
  size_t start = 0;
  size_t end = text.size();
  // Leading space.
  while (start < text.size()) {
    size_t pos = start;
    uint32_t code = 0;
    next_code_point(text, pos, code);
    if (!is_js_space(code)) break;
    start = pos;
  }
  // Trailing space: find the last non-space code point.
  size_t last_end = start;
  for (size_t pos = start; pos < end;) {
    uint32_t code = 0;
    next_code_point(text, pos, code);
    if (!is_js_space(code)) last_end = pos;
  }
  return std::string(text.substr(start, last_end - start));
}

std::string ascii_lower(std::string_view text) {
  std::string out(text);
  for (char& c : out) {
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
  }
  return out;
}

// ---- Evaluation ---------------------------------------------------------------

const char* reason_name(Reason reason) {
  switch (reason) {
    case Reason::Off: return "off";
    case Reason::Tester: return "tester";
    case Reason::Rollout: return "rollout";
    case Reason::Fallback: return "fallback";
  }
  return "fallback";
}

Evaluation evaluate(const FlagRule* flag, const User& user, std::optional<int64_t> version,
                    bool fallback) {
  if (!flag) return {fallback, Reason::Fallback, version};
  if (!flag->enabled) return {false, Reason::Off, version};

  std::string email = user.email ? ascii_lower(js_trim(*user.email)) : std::string();
  if (!email.empty()) {
    for (const auto& item : flag->override_emails) {
      if (ascii_lower(js_trim(item)) == email) return {true, Reason::Tester, version};
    }
  }
  const std::string* key = user.key && !user.key->empty() ? &*user.key : nullptr;
  if (!key) {
    if (flag->rollout_bps != 0 && flag->rollout_bps != 10000) {
      return {fallback, Reason::Fallback, version};
    }
    return {flag->rollout_bps == 10000, Reason::Rollout, version};
  }
  return {bucket(flag->key, *key) < flag->rollout_bps, Reason::Rollout, version};
}

std::optional<int64_t> parse_version(const Json* value) {
  if (!value) return std::nullopt;
  auto number = value->as_integer();
  if (!number || *number < 0 || *number > kMaxSafeInteger) return std::nullopt;
  return number;
}

std::optional<Ruleset> parse_ruleset(std::string_view body) {
  auto document = parse_json(body);
  if (!document || !document->as_object()) return std::nullopt;
  const auto* environment_id = document->get("environmentId");
  auto version = parse_version(document->get("version"));
  const auto* flags = document->get("flags");
  if (!environment_id || !environment_id->as_string() || !version || !flags || !flags->as_array()) {
    return std::nullopt;
  }
  Ruleset ruleset;
  ruleset.environment_id = *environment_id->as_string();
  ruleset.version = *version;
  for (const auto& item : *flags->as_array()) {
    const auto* key = item.get("key");
    const auto* enabled = item.get("enabled");
    const auto* rollout = item.get("rolloutBps");
    const auto* emails = item.get("overrideEmails");
    auto bps = rollout ? rollout->as_integer() : std::nullopt;
    if (!key || !key->as_string() || !enabled || !enabled->as_bool() || !bps || *bps < 0 ||
        *bps > 10000 || !emails || !emails->as_array()) {
      return std::nullopt;
    }
    FlagRule rule{*key->as_string(), *enabled->as_bool(), *bps, {}};
    for (const auto& email : *emails->as_array()) {
      if (!email.as_string()) return std::nullopt;
      rule.override_emails.push_back(*email.as_string());
    }
    ruleset.flags[rule.key] = std::move(rule);
  }
  return ruleset;
}

}  // namespace detail
}  // namespace flagbearer
