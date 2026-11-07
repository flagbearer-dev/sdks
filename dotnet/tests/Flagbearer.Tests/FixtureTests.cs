using System.Text.Json;

namespace Flagbearer.Tests;

public class FixtureTests
{
    private static readonly JsonElement Fixtures = JsonDocument.Parse(File.ReadAllText(Path.Combine(
        AppContext.BaseDirectory, "evaluation.json"))).RootElement;

    public static IEnumerable<object[]> Buckets() =>
        Fixtures.GetProperty("buckets").EnumerateArray().Select(item => new object[]
        {
            item.GetProperty("flag").GetString()!, item.GetProperty("user").GetString()!, item.GetProperty("bucket").GetInt64(),
        });

    public static IEnumerable<object[]> Cases() =>
        Fixtures.GetProperty("cases").EnumerateArray().Select(item => new object[] { item.GetProperty("name").GetString()!, item.GetRawText() });

    [Theory]
    [MemberData(nameof(Buckets))]
    public void Bucket(string flag, string user, long expected) => Assert.Equal(expected, Evaluator.Bucket(flag, user));

    [Theory]
    [MemberData(nameof(Cases))]
    public void Case(string name, string raw)
    {
        _ = name;
        var item = JsonDocument.Parse(raw).RootElement;
        FlagRule? rule = null;
        var flag = item.GetProperty("flag");
        if (flag.ValueKind != JsonValueKind.Null)
        {
            var ruleset = Evaluator.ParseRuleset(System.Text.Encoding.UTF8.GetBytes(
                $"{{\"environmentId\":\"1\",\"version\":7,\"flags\":[{flag.GetRawText()}]}}"));
            Assert.NotNull(ruleset);
            rule = ruleset!.Flags.Values.Single();
        }
        var userElement = item.GetProperty("user");
        var user = new User(
            userElement.TryGetProperty("key", out var key) ? key.GetString() : null,
            userElement.TryGetProperty("email", out var email) ? email.GetString() : null);
        var result = Evaluator.Evaluate(rule, user, 7, item.GetProperty("default").GetBoolean());
        var expect = item.GetProperty("expect");
        Assert.Equal(expect.GetProperty("value").GetBoolean(), result.Value);
        Assert.Equal(expect.GetProperty("reason").GetString(), result.Reason);
        Assert.Equal(7, result.ConfigurationVersion);
    }

    [Theory]
    [InlineData("""{"key":"a","enabled":true,"rolloutBps":10001,"overrideEmails":[]}""")]
    [InlineData("""{"key":"a","enabled":true,"rolloutBps":50.5,"overrideEmails":[]}""")]
    [InlineData("""{"key":"a","enabled":true,"rolloutBps":true,"overrideEmails":[]}""")]
    [InlineData("""{"key":"a","enabled":1,"rolloutBps":0,"overrideEmails":[]}""")]
    [InlineData("""{"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":[1]}""")]
    [InlineData("""{"key":"a","enabled":true,"rolloutBps":0}""")]
    public void InvalidRulesRejectTheWholeRuleset(string flag)
    {
        var body = """{"environmentId":"1","version":1,"flags":[{"key":"b","enabled":true,"rolloutBps":0,"overrideEmails":[]},""" + flag + "]}";
        Assert.Null(Evaluator.ParseRuleset(System.Text.Encoding.UTF8.GetBytes(body)));
    }

    [Fact]
    public void TrailingDataIsRejectedAndIntegralFloatsAccepted()
    {
        Assert.Null(Evaluator.ParseRuleset("""{"environmentId":"1","version":1,"flags":[]} trailing"""u8));
        Assert.NotNull(Evaluator.ParseRuleset("""{"environmentId":"1","version":1.0,"flags":[]}"""u8));
    }
}
