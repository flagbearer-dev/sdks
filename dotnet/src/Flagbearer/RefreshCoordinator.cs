using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace Flagbearer;

/// <summary>Every delay the client uses. Tests shorten them.</summary>
internal sealed record Timings
{
    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan PollInterval { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan RetryBase { get; init; } = TimeSpan.FromSeconds(1);
    public TimeSpan MaxRetry { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan MaxVersionRefreshDelay { get; init; } = TimeSpan.FromSeconds(2);
    public TimeSpan StreamConnectTimeout { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan StreamIdleTimeout { get; init; } = TimeSpan.FromSeconds(45);
    public TimeSpan ExposureFlushInterval { get; init; } = TimeSpan.FromSeconds(30);
    public TimeSpan ExposureTimeout { get; init; } = TimeSpan.FromSeconds(10);
}

/// <summary>
/// Decides when to fetch the ruleset. One worker task makes every fetch, so
/// there is never more than one in flight.
/// </summary>
internal sealed class RefreshCoordinator
{
    private readonly Func<CancellationToken, Task<Ruleset?>> _fetch; // null: unchanged (304)
    private readonly Action<Ruleset> _apply;
    private readonly Action _onPermanentFailure;
    private readonly Timings _timings;
    private readonly DebugLog _debug;
    private readonly CancellationTokenSource _closed = new(); // aborts a fetch in flight
    private readonly SemaphoreSlim _wake = new(0);
    private readonly object _gate = new();

    private bool _stopped;
    private bool _blocked;
    private bool _streaming;
    private long? _version;
    private long? _wanted;
    private int _attempt;
    private DateTimeOffset _notBefore; // nothing is fetched before this: backoff or Retry-After
    private DateTimeOffset? _retryAt;
    private DateTimeOffset? _pollAt;
    private DateTimeOffset? _versionAt;
    private bool _requested;
    private bool _inFlight;
    private List<TaskCompletionSource> _waiters = new();

    public RefreshCoordinator(
        Func<CancellationToken, Task<Ruleset?>> fetch,
        Action<Ruleset> apply,
        Action onPermanentFailure,
        Timings timings,
        DebugLog debug)
    {
        _fetch = fetch;
        _apply = apply;
        _onPermanentFailure = onPermanentFailure;
        _timings = timings;
        _debug = debug;
        lock (_gate)
        {
            EnsurePolling();
        }
        Worker = Task.Run(RunAsync);
    }

    internal Task Worker { get; }

    private static DateTimeOffset Now => DateTimeOffset.UtcNow;

    private void Notify()
    {
        if (_wake.CurrentCount == 0)
        {
            _wake.Release();
        }
    }

    public async Task SyncAsync(CancellationToken cancellationToken)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_stopped, typeof(FlagbearerClient));
            _blocked = false;
            EnsurePolling();
            _waiters.Add(done);
            if (_inFlight)
            {
                _debug.Log("Joining active refresh");
            }
            else
            {
                _retryAt = null;
                _requested = true;
            }
        }
        Notify();
        await done.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
    }

    public void RefreshInBackground()
    {
        lock (_gate)
        {
            BackgroundLocked();
        }
        Notify();
    }

    public void OnVersion(long version)
    {
        lock (_gate)
        {
            if (_stopped)
            {
                return;
            }
            _debug.Log("Stream version received", ("version", version), ("currentVersion", _version));
            _wanted = _wanted is { } wanted ? Math.Max(wanted, version) : version;
            if (Behind() && _versionAt is null && !_inFlight)
            {
                _versionAt = Now + _timings.MaxVersionRefreshDelay * Random.Shared.NextDouble();
            }
        }
        Notify();
    }

    public void SetStreaming(bool connected, bool refresh)
    {
        lock (_gate)
        {
            if (_stopped)
            {
                return;
            }
            if (_streaming != connected)
            {
                _debug.Log(connected ? "Stream connected" : "Stream disconnected; using polling");
            }
            _streaming = connected;
            if (connected)
            {
                ClearPolling();
                if (refresh)
                {
                    BackgroundLocked();
                }
            }
            else
            {
                EnsurePolling();
            }
        }
        Notify();
    }

    public void Close()
    {
        List<TaskCompletionSource> waiters;
        lock (_gate)
        {
            if (_stopped)
            {
                return;
            }
            _stopped = true;
            _debug.Log("Coordinator closed");
            waiters = _waiters;
            _waiters = new();
        }
        _closed.Cancel();
        Notify();
        foreach (var waiter in waiters)
        {
            waiter.TrySetException(new ObjectDisposedException(nameof(FlagbearerClient)));
        }
    }

    // The helpers below expect _gate to be held.

    private bool Behind() => _wanted is { } wanted && (_version is not { } version || version < wanted);

    private void BackgroundLocked()
    {
        if (_stopped || _blocked || _retryAt is not null || _inFlight)
        {
            return;
        }
        _requested = true;
    }

    private void EnsurePolling()
    {
        if (_stopped || _blocked || _streaming || _pollAt is not null)
        {
            return;
        }
        var interval = Http.Jitter(_timings.PollInterval, 0.1);
        _debug.Log("Polling scheduled", ("intervalMs", (long)interval.TotalMilliseconds));
        _pollAt = Now + interval;
    }

    private void ClearPolling()
    {
        if (_pollAt is not null)
        {
            _debug.Log("Polling disabled");
        }
        _pollAt = null;
    }

    private void ScheduleRetry(Exception? error)
    {
        var delay = Http.Backoff(_attempt, _timings.RetryBase, _timings.MaxRetry);
        _attempt++;
        if (error is FlagbearerException failure)
        {
            delay = Http.Max(delay, failure.RetryAfter);
        }
        _notBefore = Now + delay;
        _retryAt = _notBefore;
        _debug.Log("Refresh retry scheduled", ("delayMs", (long)delay.TotalMilliseconds), ("attempt", _attempt));
    }

    /// <summary>How long until the worker has something to do, or null if never.</summary>
    private TimeSpan? NextDue(DateTimeOffset now)
    {
        if (_requested)
        {
            return Http.Max(TimeSpan.Zero, _notBefore - now);
        }
        DateTimeOffset? next = null;
        foreach (var at in new[] { _retryAt, _pollAt, _versionAt })
        {
            if (at is { } value && (next is null || value < next))
            {
                next = value;
            }
        }
        return next is { } due ? Http.Max(TimeSpan.Zero, due - now) : null;
    }

    /// <summary>Turns any timer that has fired into a fetch request.</summary>
    private bool TakeDue(DateTimeOffset now)
    {
        if (_versionAt is { } versionAt && versionAt <= now)
        {
            _versionAt = null;
            if (Behind())
            {
                BackgroundLocked();
            }
        }
        if (_retryAt is { } retryAt && retryAt <= now)
        {
            _retryAt = null;
            BackgroundLocked();
        }
        if (_pollAt is { } pollAt && pollAt <= now)
        {
            _pollAt = null;
            BackgroundLocked();
            EnsurePolling();
        }
        return _requested && now >= _notBefore;
    }

    // The worker.

    private async Task RunAsync()
    {
        while (true)
        {
            List<TaskCompletionSource> waiters;
            while (true)
            {
                TimeSpan? delay;
                lock (_gate)
                {
                    if (_stopped)
                    {
                        return;
                    }
                    var now = Now;
                    if (TakeDue(now))
                    {
                        _requested = false;
                        _inFlight = true;
                        _versionAt = null;
                        waiters = _waiters;
                        _waiters = new();
                        break;
                    }
                    delay = NextDue(now);
                }
                try
                {
                    var wait = delay is { } value
                        ? Http.Min(value + TimeSpan.FromMilliseconds(1), TimeSpan.FromDays(1))
                        : Timeout.InfiniteTimeSpan;
                    await _wake.WaitAsync(wait, _closed.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
            }

            Exception? error = null;
            try
            {
                await RefreshAsync().ConfigureAwait(false);
            }
            catch (Exception caught)
            {
                error = caught;
            }

            bool stopped;
            lock (_gate)
            {
                _inFlight = false;
                // Sync calls that arrived during this fetch join it.
                waiters.AddRange(_waiters);
                _waiters = new();
                stopped = _stopped;
            }
            foreach (var waiter in waiters)
            {
                if (error is null)
                {
                    waiter.TrySetResult();
                }
                else
                {
                    waiter.TrySetException(error);
                }
            }
            if (error is not null && !stopped && waiters.Count == 0)
            {
                DebugLog.Warn($"Flagbearer sync failed: {error.Message}");
            }
        }
    }

    /// <summary>Fetches once, and again while a stream announcement is still ahead.</summary>
    private async Task RefreshAsync()
    {
        while (true)
        {
            _debug.Log("Refresh started");
            Ruleset? result;
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
                timeout.CancelAfter(_timings.RequestTimeout);
                try
                {
                    result = await _fetch(timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException error) when (!_closed.IsCancellationRequested)
                {
                    throw new FlagbearerException("Flagbearer request timed out", true, inner: error);
                }
            }
            catch (Exception error)
            {
                var permanent = false;
                lock (_gate)
                {
                    if (_stopped)
                    {
                        throw;
                    }
                    if (error is FlagbearerException { IsPermanent: true })
                    {
                        _debug.Log("Automatic refresh stopped after permanent failure");
                        _blocked = true;
                        _streaming = false;
                        _retryAt = null;
                        ClearPolling();
                        permanent = true;
                    }
                    else
                    {
                        ScheduleRetry(error);
                    }
                }
                if (permanent)
                {
                    _onPermanentFailure();
                }
                throw;
            }

            lock (_gate)
            {
                if (_stopped)
                {
                    return;
                }
                if (result is null)
                {
                    _debug.Log("Snapshot unchanged", ("version", _version));
                    if (Behind())
                    {
                        // The backend is still behind: retry later rather than spin.
                        ScheduleRetry(null);
                    }
                    else
                    {
                        _attempt = 0;
                        _notBefore = default;
                    }
                    return;
                }
                var previous = _version;
                if (previous is null || result.Version >= previous)
                {
                    _version = result.Version;
                    _apply(result);
                    _debug.Log("Snapshot applied", ("version", result.Version), ("previousVersion", previous));
                }
                else
                {
                    _debug.Log("Older snapshot ignored", ("version", result.Version), ("currentVersion", previous));
                }
                if (!Behind())
                {
                    _attempt = 0;
                    _notBefore = default;
                    return;
                }
                if (previous is not null && result.Version <= previous)
                {
                    ScheduleRetry(null);
                    return;
                }
            }
            // An announcement arrived during the request: fetch again now.
        }
    }
}
