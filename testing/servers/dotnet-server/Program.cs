// The .NET SDK behind the same GET /flags and GET /flush routes as node-server.ts.
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Flagbearer;

var key = Environment.GetEnvironmentVariable("FLAGBEARER_SERVER_KEY");
if (string.IsNullOrEmpty(key))
{
    Console.Error.WriteLine("The integration fixture requires FLAGBEARER_SERVER_KEY.");
    return 1;
}
var url = Environment.GetEnvironmentVariable("FLAGBEARER_URL");
var port = int.Parse(Environment.GetEnvironmentVariable("PORT") is { Length: > 0 } value ? value : "0");

// One client for the whole server, not one per HTTP request.
await using var flags = new FlagbearerClient(key, new FlagbearerOptions
{
    Url = string.IsNullOrEmpty(url) ? FlagbearerOptions.DefaultUrl : url,
    Debug = true,
});
try
{
    await flags.SyncAsync();
    Console.WriteLine("Initial Flagbearer ruleset loaded.");
}
catch (Exception error)
{
    Console.WriteLine($"Initial sync failed; serving defaults while the SDK recovers. {error.Message}");
}

// HttpListener needs a concrete port, so pick a free one when PORT is 0.
if (port == 0)
{
    var probe = new TcpListener(IPAddress.Loopback, 0);
    probe.Start();
    port = ((IPEndPoint)probe.LocalEndpoint).Port;
    probe.Stop();
}
var listener = new HttpListener();
listener.Prefixes.Add($"http://127.0.0.1:{port}/");
listener.Start();

var stopped = new TaskCompletionSource();
using var term = PosixSignalRegistration.Create(PosixSignal.SIGTERM, context => { context.Cancel = true; stopped.TrySetResult(); });
using var interrupt = PosixSignalRegistration.Create(PosixSignal.SIGINT, context => { context.Cancel = true; stopped.TrySetResult(); });

Console.WriteLine($".NET SDK fixture listening on http://127.0.0.1:{port}");

_ = Task.Run(async () =>
{
    while (listener.IsListening)
    {
        HttpListenerContext context;
        try
        {
            context = await listener.GetContextAsync();
        }
        catch
        {
            return;
        }
        _ = Task.Run(() => Serve(context));
    }
});

await stopped.Task;
listener.Stop();
return 0;

async Task Serve(HttpListenerContext context)
{
    var request = context.Request;
    try
    {
        if (request.HttpMethod != "GET")
        {
            Reply(context.Response, 405, new { error = "Use GET." });
            return;
        }
        switch (request.Url!.AbsolutePath)
        {
            // Uploads pending exposures now, so a test sees whether the server took them.
            case "/flush":
                try
                {
                    await flags.FlushAsync();
                    Reply(context.Response, 200, new { flushed = true });
                }
                catch (Exception error)
                {
                    Reply(context.Response, 502, new { error = error.Message });
                }
                return;
            case "/flags":
                var flag = request.QueryString["flag"];
                var fallback = request.QueryString["default"] ?? "false";
                if (string.IsNullOrEmpty(flag) || (fallback != "true" && fallback != "false"))
                {
                    Reply(context.Response, 400, new { error = "Provide flag, and optionally default=true or default=false." });
                    return;
                }
                var user = new User(request.QueryString["userKey"], request.QueryString["email"]);
                // Local evaluation: this request does not call Flagbearer's API.
                var enabled = flags.IsEnabled(flag, user, fallback == "true");
                Reply(context.Response, 200, new { flag, user = new { key = user.Key, email = user.Email }, enabled });
                return;
            default:
                Reply(context.Response, 404, new { error = "Not found." });
                return;
        }
    }
    catch
    {
        context.Response.Abort();
    }
}

static void Reply(HttpListenerResponse response, int status, object body)
{
    var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(body));
    response.StatusCode = status;
    response.ContentType = "application/json";
    response.Headers["Cache-Control"] = "no-store";
    response.ContentLength64 = bytes.Length;
    response.OutputStream.Write(bytes);
    response.Close();
}
