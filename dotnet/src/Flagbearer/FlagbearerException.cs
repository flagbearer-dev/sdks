using System;

namespace Flagbearer;

/// <summary>A failed request to Flagbearer.</summary>
public sealed class FlagbearerException : Exception
{
    internal FlagbearerException(string message, bool retryable, TimeSpan retryAfter = default, Exception? inner = null)
        : base(message, inner)
    {
        IsRetryable = retryable;
        RetryAfter = retryAfter;
    }

    /// <summary>
    /// True for failures retrying won't fix, such as a revoked key or an invalid ruleset.
    /// Automatic refreshes stop until <see cref="FlagbearerClient.SyncAsync"/> is called.
    /// </summary>
    public bool IsPermanent => !IsRetryable;

    internal bool IsRetryable { get; }

    internal TimeSpan RetryAfter { get; }
}
