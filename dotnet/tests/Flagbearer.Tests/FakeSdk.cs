using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace Flagbearer.Tests;

internal sealed record Rule(string Key, bool Enabled = true, int RolloutBps = 10000)
{
    public object Json => new { key = Key, enabled = Enabled, rolloutBps = RolloutBps, overrideEmails = Array.Empty<string>() };
}

/// <summary>The SDK server's three routes. Tests set what it returns and read what it got.</summary>
internal sealed class FakeSdk : IDisposable
{
    private readonly HttpListener _listener = new();
    private readonly CancellationTokenSource _stopping = new();
    private readonly object _gate = new();
    private readonly TaskCompletionSource _streamOpened = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public int Version = 1;
    public string EnvironmentId = "env-1";
    public List<Rule> Flags = [new("checkout")];
    public string? RulesetBody;
    public Queue<int> RulesetStatuses = new();
    public int StreamStatus = 200;
    public Queue<int> ExposureStatuses = new();
    public readonly List<string?> RulesetRequests = new();
    public readonly List<JsonElement> Exposures = new();
    public int StreamConnections;
    public readonly Channel<string> Events = Channel.CreateUnbounded<string>();

    public FakeSdk()
    {
        var port = FreePort();
        Url = $"http://127.0.0.1:{port}";
        _listener.Prefixes.Add(Url + "/");
        _listener.Start();
        _ = Task.Run(AcceptAsync);
    }

    public string Url { get; }

    public Task StreamOpened => _streamOpened.Task;

    public void Set(Action<FakeSdk> change)
    {
        lock (_gate)
        {
            change(this);
        }
    }

    public T Read<T>(Func<FakeSdk, T> read)
    {
        lock (_gate)
        {
            return read(this);
        }
    }

    public void Announce(int version) => Events.Writer.TryWrite($"event: version\ndata: {{\"version\":{version}}}\n\n");

    public List<JsonElement> Uploads() => Read(fake => fake.Exposures.ToList());

    public void Dispose()
    {
        _stopping.Cancel();
        try
        {
            _listener.Stop();
            _listener.Close();
        }
        catch
        {
        }
    }

    private static int FreePort()
    {
        var socket = new TcpListener(IPAddress.Loopback, 0);
        socket.Start();
        var port = ((IPEndPoint)socket.LocalEndpoint).Port;
        socket.Stop();
        return port;
    }

    private async Task AcceptAsync()
    {
        while (!_stopping.IsCancellationRequested)
        {
            HttpListenerContext context;
            try
            {
                context = await _listener.GetContextAsync();
            }
            catch
            {
                return;
            }
            _ = Task.Run(() => ServeAsync(context));
        }
    }

    private async Task ServeAsync(HttpListenerContext context)
    {
        var request = context.Request;
        var response = context.Response;
        try
        {
            // A working ruleset, reachable only by following a redirect.
            if (request.Url!.AbsolutePath == "/moved")
            {
                Finish(response, 200, """{"environmentId":"env-1","version":9,"flags":[]}""");
                return;
            }
            if (request.Headers["Authorization"] != "Bearer fbs_test")
            {
                Finish(response, 401);
                return;
            }
            switch (request.Url!.AbsolutePath)
            {
                case "/v1/ruleset":
                    ServeRuleset(request, response);
                    break;
                case "/v1/stream":
                    await ServeStreamAsync(response);
                    break;
                case "/v1/exposures":
                    ServeExposures(request, response);
                    break;
                default:
                    Finish(response, 404);
                    break;
            }
        }
        catch
        {
            try
            {
                response.Abort();
            }
            catch
            {
            }
        }
    }

    private void ServeRuleset(HttpListenerRequest request, HttpListenerResponse response)
    {
        int status;
        string etag, body;
        lock (_gate)
        {
            RulesetRequests.Add(request.Headers["If-None-Match"]);
            status = RulesetStatuses.Count > 0 ? RulesetStatuses.Dequeue() : 200;
            etag = $"\"{Version}\"";
            body = RulesetBody ?? JsonSerializer.Serialize(new
            {
                environmentId = EnvironmentId,
                version = Version,
                flags = Flags.Select(flag => flag.Json),
            });
        }
        if (status is >= 300 and < 400)
        {
            response.Headers["Location"] = Url + "/moved";
            Finish(response, status);
        }
        else if (status != 200)
        {
            response.Headers["Retry-After"] = "0";
            Finish(response, status);
        }
        else if (request.Headers["If-None-Match"] == etag)
        {
            Finish(response, 304);
        }
        else
        {
            response.Headers["ETag"] = etag;
            Finish(response, 200, body);
        }
    }

    private async Task ServeStreamAsync(HttpListenerResponse response)
    {
        int status, version;
        lock (_gate)
        {
            StreamConnections++;
            status = StreamStatus;
            version = Version;
        }
        if (status != 200)
        {
            Finish(response, status);
            return;
        }
        response.StatusCode = 200;
        response.ContentType = "text/event-stream";
        response.SendChunked = true;
        var output = response.OutputStream;
        await Write(output, $"retry: 5000\n\nevent: version\ndata: {{\"version\":{version}}}\n\n");
        _streamOpened.TrySetResult();
        await foreach (var item in Events.Reader.ReadAllAsync(_stopping.Token))
        {
            await Write(output, item);
        }
    }

    private void ServeExposures(HttpListenerRequest request, HttpListenerResponse response)
    {
        using var reader = new StreamReader(request.InputStream);
        var body = JsonDocument.Parse(reader.ReadToEnd()).RootElement.Clone();
        int status;
        lock (_gate)
        {
            Exposures.Add(body);
            status = ExposureStatuses.Count > 0 ? ExposureStatuses.Dequeue() : 200;
        }
        if (status != 200)
        {
            Finish(response, status);
            return;
        }
        var events = body.GetProperty("events").GetArrayLength();
        Finish(response, 200, $"{{\"accepted\":{events},\"rejectedChecks\":0}}");
    }

    private static async Task Write(Stream output, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        await output.WriteAsync(bytes);
        await output.FlushAsync();
    }

    private static void Finish(HttpListenerResponse response, int status, string? body = null)
    {
        response.StatusCode = status;
        if (body is not null)
        {
            var bytes = Encoding.UTF8.GetBytes(body);
            response.ContentType = "application/json";
            response.ContentLength64 = bytes.Length;
            response.OutputStream.Write(bytes);
        }
        response.Close();
    }
}
