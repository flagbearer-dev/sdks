using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Flagbearer;

internal static class Reasons
{
    public const string Off = "off";
    public const string Tester = "tester";
    public const string Rollout = "rollout";
    public const string Fallback = "fallback";
}

internal sealed record FlagRule(string Key, bool Enabled, long RolloutBps, IReadOnlyList<string> OverrideEmails);

internal sealed record Ruleset(string EnvironmentId, long Version, IReadOnlyDictionary<string, FlagRule> Flags);

internal readonly record struct Evaluation(bool Value, string Reason, long? ConfigurationVersion);

internal static class Evaluator
{
    private const long MaxSafeInteger = (1L << 53) - 1;

    /// <summary>A user's place in a flag's rollout, 0–9999. Below RolloutBps means on.</summary>
    public static long Bucket(string flagKey, string userKey)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(flagKey + ":" + userKey));
        // The digest read as a big-endian integer, mod 10000, one byte at a time.
        long remainder = 0;
        foreach (var b in digest)
        {
            remainder = (remainder * 256 + b) % 10000;
        }
        return remainder;
    }

    public static Evaluation Evaluate(FlagRule? flag, User user, long? version, bool fallback)
    {
        if (flag is null)
        {
            return new(fallback, Reasons.Fallback, version);
        }
        if (!flag.Enabled)
        {
            return new(false, Reasons.Off, version);
        }
        var email = (user.Email ?? "").Trim().ToLowerInvariant();
        if (email.Length > 0)
        {
            foreach (var item in flag.OverrideEmails)
            {
                if (item.Trim().ToLowerInvariant() == email)
                {
                    return new(true, Reasons.Tester, version);
                }
            }
        }
        if (string.IsNullOrEmpty(user.Key))
        {
            if (flag.RolloutBps != 0 && flag.RolloutBps != 10000)
            {
                return new(fallback, Reasons.Fallback, version);
            }
            return new(flag.RolloutBps == 10000, Reasons.Rollout, version);
        }
        return new(Bucket(flag.Key, user.Key) < flag.RolloutBps, Reasons.Rollout, version);
    }

    /// <summary>A JSON number with no fractional part, as JavaScript accepts.</summary>
    public static bool TryInteger(JsonElement value, out long number)
    {
        number = 0;
        if (value.ValueKind != JsonValueKind.Number)
        {
            return false;
        }
        if (value.TryGetInt64(out number))
        {
            return true;
        }
        if (value.TryGetDouble(out var real) && real == Math.Truncate(real) && Math.Abs(real) <= MaxSafeInteger)
        {
            number = (long)real;
            return true;
        }
        return false;
    }

    public static bool TryVersion(JsonElement value, out long version) =>
        TryInteger(value, out version) && version >= 0 && version <= MaxSafeInteger;

    /// <summary>Reads a GET /v1/ruleset body, rejecting it whole if any part is invalid.</summary>
    public static Ruleset? ParseRuleset(ReadOnlySpan<byte> body)
    {
        JsonDocument document;
        try
        {
            // Like JSON.parse, this rejects anything but whitespace after the value.
            document = JsonDocument.Parse(body.ToArray());
        }
        catch (JsonException)
        {
            return null;
        }
        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object
                || !root.TryGetProperty("environmentId", out var environment) || environment.ValueKind != JsonValueKind.String
                || !root.TryGetProperty("version", out var versionElement) || !TryVersion(versionElement, out var version)
                || !root.TryGetProperty("flags", out var flags) || flags.ValueKind != JsonValueKind.Array)
            {
                return null;
            }
            var rules = new Dictionary<string, FlagRule>();
            foreach (var item in flags.EnumerateArray())
            {
                var rule = ParseFlagRule(item);
                if (rule is null)
                {
                    return null;
                }
                rules[rule.Key] = rule;
            }
            return new Ruleset(environment.GetString()!, version, rules);
        }
    }

    private static FlagRule? ParseFlagRule(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object
            || !item.TryGetProperty("key", out var key) || key.ValueKind != JsonValueKind.String
            || !item.TryGetProperty("enabled", out var enabled)
            || (enabled.ValueKind != JsonValueKind.True && enabled.ValueKind != JsonValueKind.False)
            || !item.TryGetProperty("rolloutBps", out var rolloutElement) || !TryInteger(rolloutElement, out var rollout)
            || rollout < 0 || rollout > 10000
            || !item.TryGetProperty("overrideEmails", out var emails) || emails.ValueKind != JsonValueKind.Array)
        {
            return null;
        }
        var overrides = new List<string>();
        foreach (var email in emails.EnumerateArray())
        {
            if (email.ValueKind != JsonValueKind.String)
            {
                return null;
            }
            overrides.Add(email.GetString()!);
        }
        return new FlagRule(key.GetString()!, enabled.GetBoolean(), rollout, overrides);
    }
}
