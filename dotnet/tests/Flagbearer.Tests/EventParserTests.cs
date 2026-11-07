using System.Text;

namespace Flagbearer.Tests;

public class EventParserTests
{
    private static List<long> Parse(params string[] pieces)
    {
        var versions = new List<long>();
        var parser = new EventParser(versions.Add);
        foreach (var piece in pieces)
        {
            parser.Feed(Encoding.UTF8.GetBytes(piece));
        }
        return versions;
    }

    [Fact]
    public void ReadsVersionEventsAndIgnoresOthers() =>
        Assert.Equal([4L], Parse(
            "retry: 9000\n\n",
            ": comment\n",
            "event: version\ndata: {\"version\":4}\n\n",
            "event: ping\ndata: {}\n\n",
            "data: {\"version\":99}\n\n"));

    [Theory]
    [InlineData("\n")]
    [InlineData("\r\n")]
    [InlineData("\r")]
    public void AnyLineEndingSplitAnywhere(string ending)
    {
        var text = $"event: version{ending}data: {{\"version\":7}}{ending}{ending}";
        for (var cut = 0; cut <= text.Length; cut++)
        {
            // A trailing CR might be half a CRLF, so it's read once the next byte arrives.
            Assert.Equal([7L], Parse(text[..cut], text[cut..], ": heartbeat\n"));
        }
    }

    [Fact]
    public void MultiByteCharactersSplitAcrossReads()
    {
        var bytes = Encoding.UTF8.GetBytes("event: version\ndata: {\"version\":5,\"note\":\"ü😀\"}\n\n");
        for (var cut = 0; cut <= bytes.Length; cut++)
        {
            var versions = new List<long>();
            var parser = new EventParser(versions.Add);
            parser.Feed(bytes.AsSpan(0, cut));
            parser.Feed(bytes.AsSpan(cut));
            Assert.Equal([5L], versions);
        }
    }

    [Fact]
    public void MultiLineDataJoinsWithNewlines() =>
        Assert.Equal([5L], Parse("event: version\ndata: {\"version\":\ndata: 5}\n\n"));

    [Theory]
    [InlineData("nope")]
    [InlineData("""{"version":-1}""")]
    [InlineData("""{"version":true}""")]
    [InlineData("""{"version":1.5}""")]
    [InlineData("[]")]
    public void MalformedVersionsAreIgnored(string data) =>
        Assert.Empty(Parse($"event: version\ndata: {data}\n\n"));

    [Fact]
    public void OversizedEventsFailTheConnection()
    {
        Assert.Throws<InvalidDataException>(() => Parse("data: " + new string('x', EventParser.MaxEventLength) + "\n"));
        Assert.Throws<InvalidDataException>(() => Parse(new string('x', EventParser.MaxEventLength + 1)));
    }
}
