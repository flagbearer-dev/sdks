#include <cctype>
#include <cmath>
#include <cstdio>
#include <locale>
#include <sstream>

#include "internal.hpp"

namespace flagbearer {
namespace detail {

const Json* Json::get(std::string_view key) const {
  const auto* object = as_object();
  if (!object) return nullptr;
  // The last duplicate wins, as in JSON.parse.
  const Json* found = nullptr;
  for (const auto& [name, item] : *object) {
    if (name == key) found = &item;
  }
  return found;
}

const std::string* Json::as_string() const { return std::get_if<std::string>(&value); }
const bool* Json::as_bool() const { return std::get_if<bool>(&value); }
const JsonArray* Json::as_array() const { return std::get_if<JsonArray>(&value); }
const JsonObject* Json::as_object() const { return std::get_if<JsonObject>(&value); }

std::optional<int64_t> Json::as_integer() const {
  const auto* number = std::get_if<JsonNumber>(&value);
  if (!number) return std::nullopt;
  const std::string& text = number->text;
  // Plain integers parse exactly; anything else goes through a double, as in JavaScript.
  if (text.find_first_of(".eE") == std::string::npos && text.size() <= 18) {
    return std::stoll(text);
  }
  std::istringstream stream(text);
  stream.imbue(std::locale::classic());
  double parsed = 0;
  stream >> parsed;
  if (stream.fail() || !std::isfinite(parsed) || std::trunc(parsed) != parsed ||
      std::fabs(parsed) > static_cast<double>(kMaxSafeInteger)) {
    return std::nullopt;
  }
  return static_cast<int64_t>(parsed);
}

namespace {

class Parser {
 public:
  explicit Parser(std::string_view text) : text_(text) {}

  std::optional<Json> document() {
    skip_space();
    auto value = parse_value(0);
    if (!value) return std::nullopt;
    skip_space();
    if (pos_ != text_.size()) return std::nullopt;
    return value;
  }

 private:
  static constexpr int kMaxDepth = 64;

  void skip_space() {
    while (pos_ < text_.size() &&
           (text_[pos_] == ' ' || text_[pos_] == '\t' || text_[pos_] == '\n' || text_[pos_] == '\r')) {
      ++pos_;
    }
  }

  bool consume(char c) {
    if (pos_ < text_.size() && text_[pos_] == c) {
      ++pos_;
      return true;
    }
    return false;
  }

  bool literal(std::string_view word) {
    if (text_.substr(pos_, word.size()) != word) return false;
    pos_ += word.size();
    return true;
  }

  std::optional<Json> parse_value(int depth) {
    if (depth > kMaxDepth || pos_ >= text_.size()) return std::nullopt;
    char c = text_[pos_];
    if (c == '{') return parse_object(depth);
    if (c == '[') return parse_array(depth);
    if (c == '"') {
      auto text = parse_string();
      if (!text) return std::nullopt;
      return Json{std::move(*text)};
    }
    if (literal("true")) return Json{true};
    if (literal("false")) return Json{false};
    if (literal("null")) return Json{nullptr};
    return parse_number();
  }

  std::optional<Json> parse_number() {
    size_t start = pos_;
    consume('-');
    if (consume('0')) {
    } else if (pos_ < text_.size() && text_[pos_] >= '1' && text_[pos_] <= '9') {
      while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
    } else {
      return std::nullopt;
    }
    if (consume('.')) {
      size_t digits = pos_;
      while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
      if (pos_ == digits) return std::nullopt;
    }
    if (pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) {
      ++pos_;
      if (!consume('+')) consume('-');
      size_t digits = pos_;
      while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
      if (pos_ == digits) return std::nullopt;
    }
    return Json{JsonNumber{std::string(text_.substr(start, pos_ - start))}};
  }

  std::optional<unsigned> hex4() {
    if (pos_ + 4 > text_.size()) return std::nullopt;
    unsigned value = 0;
    for (int i = 0; i < 4; ++i) {
      char c = text_[pos_++];
      value <<= 4;
      if (c >= '0' && c <= '9') value |= static_cast<unsigned>(c - '0');
      else if (c >= 'a' && c <= 'f') value |= static_cast<unsigned>(c - 'a' + 10);
      else if (c >= 'A' && c <= 'F') value |= static_cast<unsigned>(c - 'A' + 10);
      else return std::nullopt;
    }
    return value;
  }

  static void append_utf8(std::string& out, unsigned code) {
    if (code < 0x80) {
      out += static_cast<char>(code);
    } else if (code < 0x800) {
      out += static_cast<char>(0xC0 | (code >> 6));
      out += static_cast<char>(0x80 | (code & 0x3F));
    } else if (code < 0x10000) {
      out += static_cast<char>(0xE0 | (code >> 12));
      out += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (code & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (code >> 18));
      out += static_cast<char>(0x80 | ((code >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (code & 0x3F));
    }
  }

  std::optional<std::string> parse_string() {
    if (!consume('"')) return std::nullopt;
    std::string out;
    while (pos_ < text_.size()) {
      unsigned char c = static_cast<unsigned char>(text_[pos_++]);
      if (c == '"') return out;
      if (c < 0x20) return std::nullopt;
      if (c != '\\') {
        out += static_cast<char>(c);
        continue;
      }
      if (pos_ >= text_.size()) return std::nullopt;
      char escape = text_[pos_++];
      switch (escape) {
        case '"': out += '"'; break;
        case '\\': out += '\\'; break;
        case '/': out += '/'; break;
        case 'b': out += '\b'; break;
        case 'f': out += '\f'; break;
        case 'n': out += '\n'; break;
        case 'r': out += '\r'; break;
        case 't': out += '\t'; break;
        case 'u': {
          auto code = hex4();
          if (!code) return std::nullopt;
          unsigned value = *code;
          if (value >= 0xD800 && value <= 0xDBFF && text_.substr(pos_, 2) == "\\u") {
            size_t mark = pos_;
            pos_ += 2;
            auto low = hex4();
            if (low && *low >= 0xDC00 && *low <= 0xDFFF) {
              value = 0x10000 + ((value - 0xD800) << 10) + (*low - 0xDC00);
            } else {
              pos_ = mark;
            }
          }
          // A lone surrogate becomes U+FFFD, as encoding it to UTF-8 would in JavaScript.
          if (value >= 0xD800 && value <= 0xDFFF) value = 0xFFFD;
          append_utf8(out, value);
          break;
        }
        default:
          return std::nullopt;
      }
    }
    return std::nullopt;
  }

  std::optional<Json> parse_array(int depth) {
    consume('[');
    JsonArray items;
    skip_space();
    if (consume(']')) return Json{std::move(items)};
    while (true) {
      skip_space();
      auto item = parse_value(depth + 1);
      if (!item) return std::nullopt;
      items.push_back(std::move(*item));
      skip_space();
      if (consume(']')) return Json{std::move(items)};
      if (!consume(',')) return std::nullopt;
    }
  }

  std::optional<Json> parse_object(int depth) {
    consume('{');
    JsonObject members;
    skip_space();
    if (consume('}')) return Json{std::move(members)};
    while (true) {
      skip_space();
      auto name = parse_string();
      if (!name) return std::nullopt;
      skip_space();
      if (!consume(':')) return std::nullopt;
      skip_space();
      auto item = parse_value(depth + 1);
      if (!item) return std::nullopt;
      members.emplace_back(std::move(*name), std::move(*item));
      skip_space();
      if (consume('}')) return Json{std::move(members)};
      if (!consume(',')) return std::nullopt;
    }
  }

  std::string_view text_;
  size_t pos_ = 0;
};

}  // namespace

std::optional<Json> parse_json(std::string_view text) { return Parser(text).document(); }

std::string json_quote(std::string_view text) {
  std::string out = "\"";
  for (size_t pos = 0; pos < text.size();) {
    unsigned char c = static_cast<unsigned char>(text[pos]);
    if (c >= 0x80) {
      // Pass valid UTF-8 through; replace invalid bytes so the server can parse it.
      size_t start = pos;
      uint32_t code = 0;
      if (next_code_point(text, pos, code)) {
        out.append(text.substr(start, pos - start));
      } else {
        out += "\\ufffd";
      }
      continue;
    }
    ++pos;
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char escaped[8];
          std::snprintf(escaped, sizeof escaped, "\\u%04x", c);
          out += escaped;
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  out += '"';
  return out;
}

}  // namespace detail
}  // namespace flagbearer
