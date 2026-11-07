using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Flagbearer;

internal sealed class ExposureReporter
{
    private const long WindowMs = 30_000;
    private const int MaxRecords = 1000;
    private const int BatchSize = 100;
    private const int MaxCheckCount = 1_000_000_000;

    private readonly HttpClient _client;
    private readonly Uri _url;
    private readonly string _key;
    private readonly string? _appVersion;
    private readonly TimeSpan _timeout;
    private readonly string _reporterId = Guid.NewGuid().ToString();
    private readonly SemaphoreSlim _sendLock = new(1, 1); // one upload at a time
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();

    // Insertion order matters: batches keep checks in the order first seen.
    private Dictionary<Identity, Active> _active = new();
    private List<Identity> _order = new();
    private readonly List<Batch> _sealed = new();
    private int _sealedEvents;
    private long _dropped;
    private long _reportedDropped;
    private DateTimeOffset _retryAt;
    private int _failures;
    private bool _closed;

    public ExposureReporter(HttpClient client, Uri url, string key, string? appVersion, Timings timings)
    {
        _client = client;
        _url = url;
        _key = key;
        _appVersion = string.IsNullOrEmpty(appVersion) ? null
            : appVersion.Length > 100 ? appVersion[..100] : appVersion;
        _timeout = timings.ExposureTimeout;
        Worker = Task.Run(() => RunAsync(timings.ExposureFlushInterval));
    }

    internal Task Worker { get; }

    private readonly record struct Identity(
        string Flag, string? Key, string? Email, bool Result, string Reason, long? Version, string Bucket);

    private sealed class Event
    {
        public required string FlagKey { get; init; }
        public required bool Result { get; init; }
        public required string Reason { get; init; }
        public required long? ConfigurationVersion { get; init; }
        public required string Bucket { get; init; }
        public required string FirstCheckedAt { get; init; }
        public required string LastCheckedAt { get; set; }
        public int CheckCount { get; set; } = 1;
    }

    private sealed record Active(string? Key, string? Email, Event Event);

    /// <summary>One upload, fixed when sealed, so a retry sends the same body and batch ID.</summary>
    private sealed class Batch(string? key, string? email)
    {
        public string Id { get; } = Guid.NewGuid().ToString();
        public string? Key { get; } = key;
        public string? Email { get; } = email;
        public List<Event> Events { get; } = new();
    }

    private static string Iso(long ms) =>
        DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime
            .ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    public void Record(string flag, User user, Evaluation details)
    {
        lock (_gate)
        {
            if (_closed)
            {
                return;
            }
            try
            {
                // string.Length counts UTF-16 units, as the server's limits do.
                if (string.IsNullOrEmpty(flag) || flag.Length > 256 || (user.Key?.Length ?? 0) > 256)
                {
                    _dropped++;
                    return;
                }
                var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var bucket = Iso(now / WindowMs * WindowMs);
                var email = user.Email is { Length: > 0 and <= 254 } raw ? raw.Trim().ToLowerInvariant() : "";
                var who = (Key: string.IsNullOrEmpty(user.Key) ? null : user.Key, Email: email.Length > 0 ? email : null);
                var identity = new Identity(flag, who.Key, who.Email, details.Value, details.Reason, details.ConfigurationVersion, bucket);
                if (_active.TryGetValue(identity, out var existing))
                {
                    if (existing.Event.CheckCount < MaxCheckCount)
                    {
                        existing.Event.CheckCount++;
                        existing.Event.LastCheckedAt = Iso(now);
                    }
                    else
                    {
                        _dropped++;
                    }
                    return;
                }
                if (_active.Count + _sealedEvents >= MaxRecords)
                {
                    _dropped++;
                    return;
                }
                _active[identity] = new Active(who.Key, who.Email, new Event
                {
                    FlagKey = flag,
                    Result = details.Value,
                    Reason = details.Reason,
                    ConfigurationVersion = details.ConfigurationVersion,
                    Bucket = bucket,
                    FirstCheckedAt = Iso(now),
                    LastCheckedAt = Iso(now),
                });
                _order.Add(identity);
            }
            catch
            {
                // Recording never fails a flag check.
                _dropped++;
            }
        }
    }

    private async Task RunAsync(TimeSpan interval)
    {
        while (true)
        {
            try
            {
                await Task.Delay(Http.Jitter(interval, 0.1), _stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            bool due;
            lock (_gate)
            {
                due = DateTimeOffset.UtcNow >= _retryAt;
            }
            if (due)
            {
                try
                {
                    await FlushAsync(CancellationToken.None).ConfigureAwait(false);
                }
                catch
                {
                    // Background uploads retry later.
                }
            }
        }
    }

    /// <summary>Uploads every pending check, throwing on the first failure.</summary>
    public async Task FlushAsync(CancellationToken cancellationToken)
    {
        await _sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await SendAsync(cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            lock (_gate)
            {
                _failures++;
                var delay = TimeSpan.FromSeconds(Math.Min(60, 1 << Math.Min(_failures, 6)));
                var at = DateTimeOffset.UtcNow + delay;
                if (at > _retryAt)
                {
                    _retryAt = at;
                }
            }
            throw;
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public async Task CloseAsync()
    {
        lock (_gate)
        {
            if (_closed)
            {
                return;
            }
            _closed = true;
        }
        _stop.Cancel();
        await FlushAsync(CancellationToken.None).ConfigureAwait(false);
    }

    // Expects _gate held. A batch never mixes users.
    private void Seal()
    {
        var open = new Dictionary<(string?, string?), Batch>();
        foreach (var identity in _order)
        {
            var item = _active[identity];
            var user = (item.Key, item.Email);
            if (!open.TryGetValue(user, out var batch) || batch.Events.Count >= BatchSize)
            {
                batch = new Batch(item.Key, item.Email);
                open[user] = batch;
                _sealed.Add(batch);
            }
            batch.Events.Add(item.Event);
            _sealedEvents++;
        }
        _active = new();
        _order = new();
    }

    private void Settle(Batch batch)
    {
        lock (_gate)
        {
            if (_sealed.Count > 0 && ReferenceEquals(_sealed[0], batch))
            {
                _sealed.RemoveAt(0);
                _sealedEvents -= batch.Events.Count;
            }
        }
    }

    private async Task SendAsync(CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            Seal();
        }
        while (true)
        {
            Batch batch;
            long dropped;
            lock (_gate)
            {
                if (_sealed.Count == 0 && _dropped <= _reportedDropped)
                {
                    return;
                }
                // Only lost checks to report: an empty batch carries the count.
                batch = _sealed.Count > 0 ? _sealed[0] : new Batch(null, null);
                dropped = _dropped;
            }
            var checks = batch.Events.Sum(item => (long)item.CheckCount);
            using var content = new ByteArrayContent(Body(batch, dropped));
            content.Headers.ContentType = new("application/json");
            using var request = new HttpRequestMessage(HttpMethod.Post, _url) { Content = content };
            request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + _key);

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(_timeout);
            HttpResponseMessage response;
            byte[] data;
            try
            {
                response = await _client.SendAsync(request, timeout.Token).ConfigureAwait(false);
                data = await response.Content.ReadAsByteArrayAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (Exception error) when (error is HttpRequestException or OperationCanceledException or IOException)
            {
                cancellationToken.ThrowIfCancellationRequested();
                throw new FlagbearerException($"Exposure upload failed: {error.Message}", true, inner: error);
            }
            using (response)
            {
                var status = (int)response.StatusCode;
                if (status < 200 || status >= 300)
                {
                    var refused = status >= 400 && status < 500 && status != 429;
                    lock (_gate)
                    {
                        var wait = Http.RetryAfter(response, DateTimeOffset.UtcNow);
                        if (wait > TimeSpan.Zero)
                        {
                            var at = DateTimeOffset.UtcNow + Http.Min(wait, TimeSpan.FromHours(1));
                            if (at > _retryAt)
                            {
                                _retryAt = at;
                            }
                        }
                        if (refused)
                        {
                            // The server won't take this batch, so retrying can't help.
                            _dropped += checks;
                            _retryAt = DateTimeOffset.UtcNow + TimeSpan.FromMinutes(1);
                        }
                    }
                    if (refused)
                    {
                        Settle(batch);
                    }
                    throw new FlagbearerException($"Exposure upload failed ({status})", !refused);
                }
            }
            var rejected = ReadReceipt(data, checks, batch.Events.Count);
            lock (_gate)
            {
                _dropped += rejected;
                _reportedDropped = Math.Max(_reportedDropped, dropped);
            }
            Settle(batch);
        }
    }

    private byte[] Body(Batch batch, long dropped)
    {
        using var stream = new MemoryStream();
        using (var json = new Utf8JsonWriter(stream))
        {
            json.WriteStartObject();
            json.WriteString("batchId", batch.Id);
            json.WriteString("reporterId", _reporterId);
            json.WriteStartObject("user");
            if (batch.Key is not null)
            {
                json.WriteString("key", batch.Key);
            }
            if (batch.Email is not null)
            {
                json.WriteString("email", batch.Email);
            }
            json.WriteEndObject();
            json.WriteStartArray("events");
            foreach (var item in batch.Events)
            {
                json.WriteStartObject();
                json.WriteString("flagKey", item.FlagKey);
                json.WriteBoolean("result", item.Result);
                json.WriteString("reason", item.Reason);
                if (item.ConfigurationVersion is { } version)
                {
                    json.WriteNumber("configurationVersion", version);
                }
                else
                {
                    json.WriteNull("configurationVersion");
                }
                json.WriteString("bucket", item.Bucket);
                json.WriteNumber("checkCount", item.CheckCount);
                json.WriteString("firstCheckedAt", item.FirstCheckedAt);
                json.WriteString("lastCheckedAt", item.LastCheckedAt);
                json.WriteEndObject();
            }
            json.WriteEndArray();
            json.WriteNumber("droppedChecks", dropped);
            json.WriteString("sdk", "dotnet");
            json.WriteString("sdkVersion", FlagbearerClient.Version);
            if (_appVersion is not null)
            {
                json.WriteString("appVersion", _appVersion);
            }
            json.WriteEndObject();
        }
        return stream.ToArray();
    }

    private static long ReadReceipt(byte[] data, long checks, int events)
    {
        try
        {
            using var document = JsonDocument.Parse(data);
            var root = document.RootElement;
            if (root.ValueKind == JsonValueKind.Object
                && root.TryGetProperty("accepted", out var acceptedElement)
                && Evaluator.TryInteger(acceptedElement, out var accepted)
                && root.TryGetProperty("rejectedChecks", out var rejectedElement)
                && Evaluator.TryInteger(rejectedElement, out var rejected)
                && accepted >= 0 && accepted <= events
                && rejected >= 0 && rejected <= checks)
            {
                return rejected;
            }
        }
        catch (JsonException)
        {
        }
        throw new FlagbearerException("Invalid exposure upload receipt", true);
    }
}
