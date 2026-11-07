using System;
using System.Globalization;
using System.Linq;

namespace Flagbearer;

/// <summary>Diagnostics for Debug = true. Never logs keys, user data, flag names or rules.</summary>
internal sealed class DebugLog(bool enabled)
{
    public bool Enabled { get; } = enabled;

    public void Log(string message, params (string Name, object? Value)[] fields)
    {
        if (!Enabled)
        {
            return;
        }
        try
        {
            if (fields.Length == 0)
            {
                Console.Error.WriteLine($"[Flagbearer] {message}");
                return;
            }
            // Same shape as the Node SDK's console.debug output: { version: 42 }.
            var details = string.Join(", ", fields.Select(field => $"{field.Name}: {Format(field.Value)}"));
            Console.Error.WriteLine($"[Flagbearer] {message} {{ {details} }}");
        }
        catch
        {
            // Diagnostics must never interrupt flag updates.
        }
    }

    public static void Warn(string message)
    {
        try
        {
            Console.Error.WriteLine(message);
        }
        catch
        {
        }
    }

    private static string Format(object? value) => value switch
    {
        null => "undefined",
        bool flag => flag ? "true" : "false",
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString() ?? "",
    };
}
