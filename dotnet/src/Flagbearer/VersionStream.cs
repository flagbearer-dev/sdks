using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Flagbearer;

/// <summary>
/// Reads Server-Sent Events fed in arbitrary pieces, calling onVersion for each
/// well-formed version event and ignoring everything else.
/// </summary>
internal sealed class EventParser(Action<long> onVersion)
{
    public const int MaxEventLength = 65_536;

    // Holds back a character split across reads; invalid bytes become U+FFFD.
    private readonly Decoder _decoder = new UTF8Encoding(false, false).GetDecoder();
    private readonly StringBuilder _data = new();
    private string _buffer = "";
    private string _event = "message";

    public void Feed(ReadOnlySpan<byte> chunk)
    {
        var chars = new char[_decoder.GetCharCount(chunk, flush: false)];
        _decoder.GetChars(chunk, chars, flush: false);
        var buffer = _buffer + new string(chars);
        var start = 0;
        for (var index = 0; index < buffer.Length; index++)
        {
            var c = buffer[index];
            if (c != '\r' && c != '\n')
            {
                continue;
            }
            // A CR at the end might be the first half of a CRLF in the next piece.
            if (c == '\r' && index == buffer.Length - 1)
            {
                break;
            }
            Line(buffer[start..index]);
            if (c == '\r' && buffer[index + 1] == '\n')
            {
                index++;
            }
            start = index + 1;
        }
        _buffer = buffer[start..];
        if (_buffer.Length > MaxEventLength)
        {
            throw new InvalidDataException("Flagbearer stream line too large");
        }
    }

    private void Line(string line)
    {
        if (line.Length == 0)
        {
            if (_event == "version" && _data.Length > 0)
            {
                var data = _data.ToString(0, _data.Length - 1);
                try
                {
                    using var document = JsonDocument.Parse(data);
                    if (document.RootElement.ValueKind == JsonValueKind.Object
                        && document.RootElement.TryGetProperty("version", out var element)
                        && Evaluator.TryVersion(element, out var version))
                    {
                        onVersion(version);
                    }
                }
                catch (JsonException)
                {
                    // Ignore malformed announcements.
                }
            }
            _event = "message";
            _data.Clear();
            return;
        }
        if (line.StartsWith(':'))
        {
            return;
        }
        var separator = line.IndexOf(':');
        var name = separator < 0 ? line : line[..separator];
        var content = separator < 0 ? "" : line[(separator + 1)..];
        if (content.StartsWith(' '))
        {
            content = content[1..];
        }
        if (name == "event")
        {
            _event = content;
        }
        else if (name == "data")
        {
            _data.Append(content).Append('\n');
            if (_data.Length > MaxEventLength)
            {
                throw new InvalidDataException("Flagbearer stream event too large");
            }
        }
    }
}

/// <summary>Holds GET /v1/stream open on its own task and reconnects with backoff.</summary>
internal sealed class VersionStream
{
    private readonly HttpClient _client;
    private readonly Uri _url;
    private readonly IReadOnlyDictionary<string, string> _headers;
    private readonly Timings _timings;
    private readonly DebugLog _debug;
    private readonly Action<long> _onVersion;
    private readonly Action _onOpen;
    private readonly Action _onDisconnect;
    private readonly CancellationTokenSource _closed = new();
    private int _attempts;

    public VersionStream(
        HttpClient client,
        Uri url,
        IReadOnlyDictionary<string, string> headers,
        Timings timings,
        DebugLog debug,
        Action<long> onVersion,
        Action onOpen,
        Action onDisconnect)
    {
        _client = client;
        _url = url;
        _headers = headers;
        _timings = timings;
        _debug = debug;
        _onVersion = onVersion;
        _onOpen = onOpen;
        _onDisconnect = onDisconnect;
        Worker = Task.Run(RunAsync);
    }

    internal Task Worker { get; }

    public void Close()
    {
        if (_closed.IsCancellationRequested)
        {
            return;
        }
        _debug.Log("Stream closed");
        try
        {
            _closed.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private async Task RunAsync()
    {
        while (!_closed.IsCancellationRequested)
        {
            var failure = await ConnectAsync().ConfigureAwait(false);
            if (_closed.IsCancellationRequested)
            {
                return;
            }
            _onDisconnect();
            if (failure is FlagbearerException { IsPermanent: true })
            {
                // Polling remains available; the next ruleset request also checks the key.
                DebugLog.Warn($"Flagbearer stream stopped; using polling: {failure.Message}");
                return;
            }
            var delay = Http.Backoff(_attempts, _timings.RetryBase, _timings.MaxRetry);
            _attempts++;
            if (failure is FlagbearerException refresh)
            {
                delay = Http.Max(delay, refresh.RetryAfter);
            }
            _debug.Log("Stream reconnect scheduled", ("delayMs", (long)delay.TotalMilliseconds));
            try
            {
                await Task.Delay(delay, _closed.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task<Exception?> ConnectAsync()
    {
        _debug.Log("Stream connecting");
        // One deadline covers both limits: connecting, then each quiet stretch.
        // Close cancels it too, which aborts a read blocked on the connection.
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
        deadline.CancelAfter(_timings.StreamConnectTimeout);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, _url);
            foreach (var (name, value) in _headers)
            {
                request.Headers.TryAddWithoutValidation(name, value);
            }
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
            using var response = await _client
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token)
                .ConfigureAwait(false);
            Http.CheckStatus(response);
            if (response.Content.Headers.ContentType?.MediaType?.ToLowerInvariant() != "text/event-stream")
            {
                throw new FlagbearerException("Invalid Flagbearer stream response", false);
            }
            await using var body = await response.Content.ReadAsStreamAsync(deadline.Token).ConfigureAwait(false);
            // Heartbeats arrive every 15 seconds, so silence past the idle limit means a dead connection.
            deadline.CancelAfter(_timings.StreamIdleTimeout);
            _onOpen();

            var parser = new EventParser(_onVersion);
            var buffer = new byte[16_384];
            while (true)
            {
                var read = await body.ReadAsync(buffer, deadline.Token).ConfigureAwait(false);
                if (read == 0)
                {
                    return null;
                }
                deadline.CancelAfter(_timings.StreamIdleTimeout);
                _attempts = 0;
                parser.Feed(buffer.AsSpan(0, read));
            }
        }
        catch (FlagbearerException error)
        {
            return error;
        }
        catch (Exception error)
        {
            return new FlagbearerException($"Flagbearer stream failed: {error.Message}", true, inner: error);
        }
    }
}
