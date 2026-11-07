using System;
using System.Globalization;
using System.Net;
using System.Net.Http;

namespace Flagbearer;

internal static class Http
{
    public static HttpClient CreateClient() =>
        new(new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            UseCookies = false,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        })
        {
            Timeout = System.Threading.Timeout.InfiniteTimeSpan,
        };

    public static TimeSpan RetryAfter(HttpResponseMessage response, DateTimeOffset now)
    {
        var header = response.Headers.RetryAfter;
        if (header?.Delta is { } delta)
        {
            return delta < TimeSpan.Zero ? TimeSpan.Zero : delta;
        }
        if (header?.Date is { } date && date > now)
        {
            return date - now;
        }
        // Fractional seconds aren't valid HTTP but the other SDKs accept them.
        if (response.Headers.TryGetValues("Retry-After", out var values))
        {
            foreach (var value in values)
            {
                if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds)
                    && seconds >= 0 && double.IsFinite(seconds))
                {
                    return TimeSpan.FromSeconds(seconds);
                }
            }
        }
        return TimeSpan.Zero;
    }

    public static void CheckStatus(HttpResponseMessage response)
    {
        var status = (int)response.StatusCode;
        if (status >= 200 && status < 300)
        {
            return;
        }
        // A 3xx lands here too: requests never follow redirects.
        throw new FlagbearerException(
            $"Flagbearer request failed: {status}",
            status == 408 || status == 429 || status >= 500,
            RetryAfter(response, DateTimeOffset.UtcNow));
    }

    public static TimeSpan Backoff(int attempt, TimeSpan baseDelay, TimeSpan cap)
    {
        var delay = Min(cap, baseDelay * (1 << Math.Min(attempt, 5)));
        return Min(cap, delay * (0.8 + Random.Shared.NextDouble() * 0.4));
    }

    public static TimeSpan Jitter(TimeSpan interval, double spread) =>
        interval * (1 - spread + Random.Shared.NextDouble() * 2 * spread);

    public static TimeSpan Min(TimeSpan a, TimeSpan b) => a < b ? a : b;

    public static TimeSpan Max(TimeSpan a, TimeSpan b) => a > b ? a : b;

    public static Uri Endpoint(Uri baseUrl, string path) =>
        new(baseUrl.GetLeftPart(UriPartial.Authority) + baseUrl.AbsolutePath.TrimEnd('/') + path);

    public static bool IsSuccess(HttpStatusCode status) => (int)status >= 200 && (int)status < 300;
}
