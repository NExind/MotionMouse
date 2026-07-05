using Microsoft.Extensions.Logging;
using System.Collections.Concurrent;
using System.IO;

namespace MotionMouse;

/// <summary>
/// Simple file logger for release builds.
///
/// Writes structured log lines to:
///   %AppData%\MotionMouse\logs\motionmouse-YYYY-MM-DD.log
///
/// One log file per day — old files are retained for 7 days
/// then deleted automatically on the next startup.
///
/// Format:
///   [HH:mm:ss.fff] [LEVEL] [Category] Message
///
/// This is intentionally simple — no third-party logging framework
/// dependency. In a future version, Serilog with rolling file sink
/// would be a drop-in upgrade if richer logging is needed.
/// </summary>
public sealed class FileLoggerProvider : ILoggerProvider
{
    private static readonly string LogDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "MotionMouse", "logs");

    private readonly string _logFilePath;
    private readonly ConcurrentQueue<string> _queue = new();
    private readonly Task _writeTask;
    private readonly CancellationTokenSource _cts = new();
    private readonly StreamWriter _writer;

    public FileLoggerProvider()
    {
        Directory.CreateDirectory(LogDirectory);
        PurgeOldLogs();

        var filename  = $"motionmouse-{DateTime.Now:yyyy-MM-dd}.log";
        _logFilePath  = Path.Combine(LogDirectory, filename);

        _writer = new StreamWriter(
            new FileStream(
                _logFilePath,
                FileMode.Append,
                FileAccess.Write,
                FileShare.Read),
            System.Text.Encoding.UTF8,
            bufferSize: 4096,
            leaveOpen: false);

        // Background task drains the queue and writes to disk
        _writeTask = Task.Run(DrainQueueAsync);
    }

    public ILogger CreateLogger(string categoryName)
        => new FileLogger(categoryName, _queue);

    private async Task DrainQueueAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            while (_queue.TryDequeue(out var line))
            {
                await _writer.WriteLineAsync(line);
            }
            await _writer.FlushAsync();
            await Task.Delay(200, _cts.Token).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Delete log files older than 7 days.
    /// </summary>
    private static void PurgeOldLogs()
    {
        try
        {
            var cutoff = DateTime.Now.AddDays(-7);
            foreach (var file in Directory.GetFiles(LogDirectory, "*.log"))
            {
                if (File.GetCreationTime(file) < cutoff)
                    File.Delete(file);
            }
        }
        catch { /* Non-fatal */ }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _writeTask.Wait(TimeSpan.FromSeconds(2)); } catch { }
        _writer.Flush();
        _writer.Dispose();
        _cts.Dispose();
    }
}

/// <summary>
/// Individual logger instance created per category.
/// </summary>
internal sealed class FileLogger : ILogger
{
    private readonly string _category;
    private readonly ConcurrentQueue<string> _queue;

    public FileLogger(string category, ConcurrentQueue<string> queue)
    {
        // Shorten category name — strip namespace prefix for readability
        _category = category.Contains('.')
            ? category[(category.LastIndexOf('.') + 1)..]
            : category;
        _queue = queue;
    }

    public IDisposable? BeginScope<TState>(TState state) where TState : notnull
        => null; // Scopes not implemented in V1

    public bool IsEnabled(LogLevel logLevel)
        => logLevel >= LogLevel.Debug;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        if (!IsEnabled(logLevel)) return;

        var level = logLevel switch
        {
            LogLevel.Trace       => "TRC",
            LogLevel.Debug       => "DBG",
            LogLevel.Information => "INF",
            LogLevel.Warning     => "WRN",
            LogLevel.Error       => "ERR",
            LogLevel.Critical    => "CRT",
            _                    => "UNK"
        };

        var message = formatter(state, exception);
        var line    = $"[{DateTime.Now:HH:mm:ss.fff}] [{level}] [{_category}] {message}";

        if (exception is not null)
            line += $"\n    {exception.GetType().Name}: {exception.Message}";

        _queue.Enqueue(line);
    }
}
