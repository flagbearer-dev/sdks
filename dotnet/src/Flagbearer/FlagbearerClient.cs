using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;

namespace Flagbearer;

/// <summary>Who a flag is checked for. Both fields are optional.</summary>
public sealed record User(string? Key = null, string? Email = null);

/// <summary>Settings for <see cref="FlagbearerClient"/>.</summary>
public sealed class FlagbearerOptions
{
    /// <summary>The production SDK address, used when <see cref="Url"/> isn't set.</summary>
    public const string DefaultUrl = "https://sdk.flagbearer.dev";

    /// <summary>The SDK server address, for staging or local development.</summary>
    public string Url { get; init; } = DefaultUrl;

    /// <summary>
    /// Logs stream connections, received versions, refreshes, retries and applied
    /// rules to stderr. Logs never include keys, user data, flag names or rules.
    /// </summary>
    public bool Debug { get; init; }

    /// <summary>Labels which deployment of your app reported each check.</summary>
    public string? AppVersion { get; init; }

    /// <summary>
    /// The handler every request goes through, such as for a proxy or custom TLS.
    /// Redirects are refused either way. The client doesn't dispose it.
    /// </summary>
    public HttpMessageHandler? HttpHandler { get; init; }

    internal Timings Timings { get; init; } = new();
}

/// <summary>
/// Checks flags against the rules in memory. Create one per process and keep it
/// for the life of the process. It's safe for concurrent use.
/// </summary>
public sealed class FlagbearerClient : IAsyncDisposable, IDisposable
{
    internal static readonly string Version =
        typeof(FlagbearerClient).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? "0.0.0";

    private readonly HttpClient _http;
    private readonly bool _ownsHttp;
    private readonly Uri _rulesetUrl;
    private readonly Dictionary<string, string> _headers;
    private readonly RefreshCoordinator _coordinator;
    private readonly VersionStream _stream;
    private readonly ExposureReporter _reporter;
    private Ruleset? _ruleset;
    private int _connected;
    private int _disposed;

    /// <summary>
    /// Starts a client: it fetches the rules and opens the stream in the
    /// background, without blocking. Await <see cref="SyncAsync"/> for the first rules.
    /// </summary>
    public FlagbearerClient(string key, FlagbearerOptions? options = null)
    {
        options ??= new FlagbearerOptions();
        if (string.IsNullOrEmpty(key) || key.Any(char.IsWhiteSpace))
        {
            throw new ArgumentException("A nonempty server SDK key is required", nameof(key));
        }
        if (!Uri.TryCreate(options.Url, UriKind.Absolute, out var baseUrl)
            || (baseUrl.Scheme != Uri.UriSchemeHttp && baseUrl.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("Flagbearer url must use HTTP or HTTPS", nameof(options));
        }

        if (options.HttpHandler is { } handler)
        {
            if (handler is SocketsHttpHandler sockets)
            {
                sockets.AllowAutoRedirect = false;
            }
            else if (handler is HttpClientHandler classic)
            {
                classic.AllowAutoRedirect = false;
            }
            _http = new HttpClient(handler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
        }
        else
        {
            _http = Http.CreateClient();
            _ownsHttp = true;
        }

        var debug = new DebugLog(options.Debug);
        _rulesetUrl = Http.Endpoint(baseUrl, "/v1/ruleset");
        _headers = new Dictionary<string, string> { ["Authorization"] = "Bearer " + key };
        _reporter = new ExposureReporter(_http, Http.Endpoint(baseUrl, "/v1/exposures"), key, options.AppVersion, options.Timings);
        _coordinator = new RefreshCoordinator(
            FetchAsync,
            ruleset => Volatile.Write(ref _ruleset, ruleset),
            () => _stream!.Close(),
            options.Timings,
            debug);
        _stream = new VersionStream(
            _http,
            Http.Endpoint(baseUrl, "/v1/stream"),
            _headers,
            options.Timings,
            debug,
            _coordinator.OnVersion,
            // After a reconnect, refetch in case an announcement was missed.
            () => _coordinator.SetStreaming(true, Interlocked.Exchange(ref _connected, 1) == 1),
            () => _coordinator.SetStreaming(false, false));
        _coordinator.RefreshInBackground();
    }

    internal VersionStream Stream => _stream;

    /// <summary>
    /// Waits for the latest rules. Throws <see cref="FlagbearerException"/> when the
    /// request fails; the client keeps retrying in the background either way.
    /// </summary>
    public Task SyncAsync(CancellationToken cancellationToken = default) => _coordinator.SyncAsync(cancellationToken);

    /// <summary>
    /// Checks a flag using only the rules in memory. Never throws or blocks on the
    /// network. <paramref name="defaultValue"/> is returned until the first rules
    /// arrive, and when the flag doesn't exist or can't be evaluated.
    /// </summary>
    public bool IsEnabled(string flag, User? user = null, bool defaultValue = false)
    {
        user ??= new User();
        var current = Volatile.Read(ref _ruleset);
        Evaluation details;
        try
        {
            FlagRule? rule = null;
            current?.Flags.TryGetValue(flag ?? "", out rule);
            details = current is null
                ? new Evaluation(defaultValue, Reasons.Fallback, null)
                : Evaluator.Evaluate(rule, user, current.Version, defaultValue);
        }
        catch
        {
            details = new Evaluation(defaultValue, Reasons.Fallback, current?.Version);
        }
        _reporter.Record(flag ?? "", user, details);
        return details.Value;
    }

    /// <summary>Uploads pending exposure reports. Throws when an upload fails.</summary>
    public Task FlushAsync(CancellationToken cancellationToken = default) => _reporter.FlushAsync(cancellationToken);

    /// <summary>
    /// Stops streaming and background work, then uploads pending exposures once,
    /// ignoring upload failures. Safe to call more than once. Rules in memory stay usable.
    /// </summary>
    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 1)
        {
            return;
        }
        _coordinator.Close();
        _stream.Close();
        try
        {
            await _reporter.CloseAsync().ConfigureAwait(false);
        }
        catch
        {
            // Shutdown continues if the last upload fails.
        }
        if (_ownsHttp)
        {
            _http.Dispose();
        }
    }

    /// <inheritdoc cref="DisposeAsync"/>
    public void Dispose() => DisposeAsync().AsTask().GetAwaiter().GetResult();

    private async Task<Ruleset?> FetchAsync(CancellationToken cancellationToken)
    {
        var current = Volatile.Read(ref _ruleset);
        using var request = new HttpRequestMessage(HttpMethod.Get, _rulesetUrl);
        foreach (var (name, value) in _headers)
        {
            request.Headers.TryAddWithoutValidation(name, value);
        }
        if (current is not null)
        {
            request.Headers.TryAddWithoutValidation("If-None-Match", $"\"{current.Version}\"");
        }
        HttpResponseMessage response;
        byte[] body;
        try
        {
            response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            body = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException error)
        {
            throw new FlagbearerException($"Flagbearer request failed: {error.Message}", true, inner: error);
        }
        using (response)
        {
            if (response.StatusCode == HttpStatusCode.NotModified)
            {
                return null;
            }
            Http.CheckStatus(response);
        }
        var next = Evaluator.ParseRuleset(body)
            ?? throw new FlagbearerException("Invalid ruleset response", false);
        if (current is not null && next.EnvironmentId != current.EnvironmentId)
        {
            throw new FlagbearerException("Ruleset environment changed unexpectedly", false);
        }
        return next;
    }
}
