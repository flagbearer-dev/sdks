using System.Text.Json;

namespace Flagbearer.Tests;

public sealed class ClientTests : IAsyncLifetime
{
    // Short enough that retries finish within a test, long enough that nothing
    // polls unless a test asks for it.
    private static readonly Timings Fast = new()
    {
        RequestTimeout = TimeSpan.FromSeconds(1),
        PollInterval = TimeSpan.FromHours(1),
        RetryBase = TimeSpan.FromMilliseconds(10),
        MaxRetry = TimeSpan.FromMilliseconds(50),
        MaxVersionRefreshDelay = TimeSpan.Zero,
        StreamConnectTimeout = TimeSpan.FromSeconds(1),
        StreamIdleTimeout = TimeSpan.FromSeconds(5),
        ExposureFlushInterval = TimeSpan.FromHours(1),
        ExposureTimeout = TimeSpan.FromSeconds(1),
    };

    private static readonly User User1 = new("user-1");

    private readonly FakeSdk _sdk = new();
    private readonly List<FlagbearerClient> _clients = new();

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        foreach (var client in _clients)
        {
            await client.DisposeAsync();
        }
        _sdk.Dispose();
    }

    private FlagbearerClient Client(Timings? timings = null, string? appVersion = null)
    {
        var client = new FlagbearerClient("fbs_test", new FlagbearerOptions
        {
            Url = _sdk.Url,
            AppVersion = appVersion,
            Timings = timings ?? Fast,
        });
        _clients.Add(client);
        return client;
    }

    /// <summary>Polls a condition the SDK reaches on its own tasks.</summary>
    private static async Task WaitFor(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow.AddSeconds(3);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < deadline, "condition not met in time");
            await Task.Delay(5);
        }
    }

    [Fact]
    public void ChecksReturnTheDefaultBeforeAnyRuleset()
    {
        _sdk.Set(fake => { for (var i = 0; i < 1000; i++) fake.RulesetStatuses.Enqueue(503); });
        var client = Client();
        Assert.True(client.IsEnabled("checkout", User1, true));
        Assert.False(client.IsEnabled("checkout", User1, false));
    }

    [Fact]
    public async Task ChecksUseTheRulesAfterASync()
    {
        var client = Client();
        await client.SyncAsync();
        Assert.True(client.IsEnabled("checkout", User1));
        Assert.True(client.IsEnabled("missing", User1, true));
    }

    [Fact]
    public async Task AStreamAnnouncementRefetchesWithTheCurrentVersion()
    {
        var client = Client();
        await client.SyncAsync();
        await _sdk.StreamOpened.WaitAsync(TimeSpan.FromSeconds(2));

        _sdk.Set(fake => { fake.Flags = [new("checkout", Enabled: false)]; fake.Version = 2; });
        _sdk.Announce(2);
        await WaitFor(() => !client.IsEnabled("checkout", User1, true));
        Assert.Contains("\"1\"", _sdk.Read(fake => fake.RulesetRequests.ToList()));
    }

    [Fact]
    public async Task AnUnchangedRulesetKeepsTheRules()
    {
        var client = Client();
        await client.SyncAsync();
        await client.SyncAsync();
        Assert.Equal("\"1\"", _sdk.Read(fake => fake.RulesetRequests[^1]));
        Assert.True(client.IsEnabled("checkout", User1));
    }

    [Fact]
    public async Task AnInvalidRulesetFailsAndKeepsTheLastRules()
    {
        var client = Client();
        await client.SyncAsync();

        _sdk.Set(fake =>
        {
            fake.Version = 2;
            fake.RulesetBody = """{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}""";
        });
        var error = await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());
        Assert.True(error.IsPermanent);
        Assert.True(client.IsEnabled("checkout", User1));

        // An explicit sync tries again after a permanent failure.
        _sdk.Set(fake => { fake.RulesetBody = null; fake.Flags = [new("checkout", Enabled: false)]; });
        await client.SyncAsync();
        Assert.False(client.IsEnabled("checkout", User1, true));
    }

    [Fact]
    public async Task SyncAfterAPermanentFailureRestartsPolling()
    {
        _sdk.Set(fake => fake.StreamStatus = 401);
        var client = Client(Fast with { PollInterval = TimeSpan.FromMilliseconds(50) });
        await client.SyncAsync();
        _sdk.Set(fake => { fake.RulesetBody = "not json"; fake.Version = 2; });
        await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());

        _sdk.Set(fake => fake.RulesetBody = null);
        await client.SyncAsync();
        _sdk.Set(fake => { fake.Flags = [new("checkout", Enabled: false)]; fake.Version = 3; });
        await WaitFor(() => !client.IsEnabled("checkout", User1, true));
    }

    [Fact]
    public async Task ARedirectIsAPermanentFailure()
    {
        _sdk.Set(fake => fake.RulesetStatuses.Enqueue(302));
        var client = Client();
        var error = await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());
        Assert.True(error.IsPermanent);
    }

    [Fact]
    public async Task ARulesetForAnotherEnvironmentIsRejected()
    {
        var client = Client();
        await client.SyncAsync();
        _sdk.Set(fake => { fake.EnvironmentId = "env-2"; fake.Flags = [new("checkout", Enabled: false)]; fake.Version = 2; });
        var error = await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());
        Assert.Contains("environment changed", error.Message);
        Assert.True(client.IsEnabled("checkout", User1));
    }

    [Fact]
    public async Task ServerErrorsRetryInTheBackground()
    {
        // Without a stream, only the retry schedule can recover.
        _sdk.Set(fake =>
        {
            fake.StreamStatus = 401;
            foreach (var status in new[] { 503, 500, 429 }) fake.RulesetStatuses.Enqueue(status);
        });
        var client = Client();
        var error = await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());
        Assert.False(error.IsPermanent);
        await WaitFor(() => client.IsEnabled("checkout", User1));
    }

    [Fact]
    public async Task SyncHonorsItsCancellationToken()
    {
        _sdk.Set(fake => { for (var i = 0; i < 5; i++) fake.RulesetStatuses.Enqueue(503); });
        var client = Client(Fast with { RetryBase = TimeSpan.FromHours(1), MaxRetry = TimeSpan.FromHours(1) });
        await Assert.ThrowsAsync<FlagbearerException>(() => client.SyncAsync());
        using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.SyncAsync(timeout.Token));
    }

    [Fact]
    public async Task TheStreamOutlivesItsConnectTimeoutAndDisposeEndsIt()
    {
        var client = Client(Fast with { StreamConnectTimeout = TimeSpan.FromMilliseconds(50) });
        await client.SyncAsync();
        await _sdk.StreamOpened.WaitAsync(TimeSpan.FromSeconds(2));
        await Task.Delay(300);
        Assert.Equal(1, _sdk.Read(fake => fake.StreamConnections));

        // The idle timeout is 5 seconds, so only an abort ends the read this soon.
        await client.DisposeAsync();
        await client.Stream.Worker.WaitAsync(TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task ASilentStreamIsDroppedAndReconnected()
    {
        // The fake sends no heartbeats, so the stream goes quiet after it opens.
        var client = Client(Fast with { StreamIdleTimeout = TimeSpan.FromMilliseconds(200) });
        await client.SyncAsync();
        await WaitFor(() => _sdk.Read(fake => fake.StreamConnections) >= 2);
    }

    [Fact]
    public async Task ARejectedStreamFallsBackToPolling()
    {
        _sdk.Set(fake => fake.StreamStatus = 401);
        var client = Client(Fast with { PollInterval = TimeSpan.FromMilliseconds(50) });
        await client.SyncAsync();
        _sdk.Set(fake => { fake.Flags = [new("checkout", Enabled: false)]; fake.Version = 2; });
        await WaitFor(() => !client.IsEnabled("checkout", User1, true));
    }

    [Fact]
    public async Task ExposuresCountRepeatedChecksAndBatchByUser()
    {
        var client = Client(appVersion: "build-7");
        await client.SyncAsync();
        for (var i = 0; i < 3; i++)
        {
            client.IsEnabled("checkout", new User("user-1", " Ann@Example.com "));
        }
        client.IsEnabled("checkout", new User("user-2"));
        await client.FlushAsync();

        var uploads = _sdk.Uploads();
        var byUser = uploads.ToDictionary(upload => upload.GetProperty("user").GetProperty("key").GetString()!);
        foreach (var upload in uploads)
        {
            Assert.Equal("dotnet", upload.GetProperty("sdk").GetString());
            Assert.Equal("1.0.1", upload.GetProperty("sdkVersion").GetString());
            Assert.Equal("build-7", upload.GetProperty("appVersion").GetString());
        }
        var first = byUser["user-1"];
        Assert.Equal("ann@example.com", first.GetProperty("user").GetProperty("email").GetString());
        var item = first.GetProperty("events")[0];
        Assert.Equal(3, item.GetProperty("checkCount").GetInt32());
        Assert.True(item.GetProperty("result").GetBoolean());
        Assert.Equal("rollout", item.GetProperty("reason").GetString());
        Assert.Equal(1, item.GetProperty("configurationVersion").GetInt64());
        var bucket = item.GetProperty("bucket").GetString()!;
        Assert.EndsWith(".000Z", bucket);
        Assert.Equal(0, DateTimeOffset.Parse(bucket).ToUnixTimeMilliseconds() % 30_000);
        Assert.Equal(1, byUser["user-2"].GetProperty("events")[0].GetProperty("checkCount").GetInt32());
        Assert.Equal(first.GetProperty("reporterId").GetString(), byUser["user-2"].GetProperty("reporterId").GetString());
    }

    [Fact]
    public async Task AFailedUploadRetriesTheSameBatch()
    {
        var client = Client();
        await client.SyncAsync();
        client.IsEnabled("checkout", User1);
        _sdk.Set(fake => fake.ExposureStatuses.Enqueue(500));
        await Assert.ThrowsAsync<FlagbearerException>(() => client.FlushAsync());
        await client.FlushAsync();
        var uploads = _sdk.Uploads();
        Assert.Equal(2, uploads.Count);
        Assert.Equal(uploads[0].GetProperty("batchId").GetString(), uploads[1].GetProperty("batchId").GetString());
    }

    [Fact]
    public async Task ARefusedUploadIsDroppedAndCountedAsLost()
    {
        var client = Client();
        await client.SyncAsync();
        client.IsEnabled("checkout", User1);
        client.IsEnabled("checkout", User1);
        _sdk.Set(fake => fake.ExposureStatuses.Enqueue(400));
        await Assert.ThrowsAsync<FlagbearerException>(() => client.FlushAsync());
        await client.FlushAsync();
        var last = _sdk.Uploads()[^1];
        Assert.Equal(0, last.GetProperty("events").GetArrayLength());
        Assert.Equal(2, last.GetProperty("droppedChecks").GetInt32());
    }

    [Fact]
    public async Task ChecksThatCantBeRecordedAreCountedAsLost()
    {
        var client = Client();
        await client.SyncAsync();
        Assert.True(client.IsEnabled(new string('x', 257), User1, true));
        // 200 emoji are 400 UTF-16 units: past the server's 256 limit.
        client.IsEnabled("checkout", new User(string.Concat(Enumerable.Repeat("😀", 200))), true);
        await client.FlushAsync();
        var last = _sdk.Uploads()[^1];
        Assert.Equal(2, last.GetProperty("droppedChecks").GetInt32());
        Assert.Equal(0, last.GetProperty("events").GetArrayLength());
    }

    [Fact]
    public async Task DisposeFlushesOnceAndKeepsRulesUsable()
    {
        var client = Client();
        await client.SyncAsync();
        client.IsEnabled("checkout", User1);
        await client.DisposeAsync();
        await client.DisposeAsync();
        client.Dispose();
        Assert.Single(_sdk.Uploads());
        Assert.True(client.IsEnabled("checkout", User1));
        await Assert.ThrowsAsync<ObjectDisposedException>(() => client.SyncAsync());
    }

    [Theory]
    [InlineData("", FlagbearerOptions.DefaultUrl)]
    [InlineData("fbs_ bad", FlagbearerOptions.DefaultUrl)]
    [InlineData("fbs_test", "ftp://example.com")]
    [InlineData("fbs_test", "not a url")]
    public void ConstructorRejectsBadKeysAndUrls(string key, string url) =>
        Assert.Throws<ArgumentException>(() => new FlagbearerClient(key, new FlagbearerOptions { Url = url }));

    [Fact]
    public void DebugLinesMatchTheOtherSdks()
    {
        var output = new StringWriter();
        var original = Console.Error;
        Console.SetError(output);
        try
        {
            new DebugLog(true).Log("Stream version received", ("version", 42L), ("currentVersion", (long?)null));
        }
        finally
        {
            Console.SetError(original);
        }
        Assert.Equal("[Flagbearer] Stream version received { version: 42, currentVersion: undefined }", output.ToString().Trim());
    }
}
