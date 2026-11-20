import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/foundation.dart';

import 'redact.dart';

enum AppErrorSource { flutter, platform, session }

/// A bounded, already-redacted record from this app process (and persisted
/// across restarts when [AppErrorLog.attachFile] is enabled).
class AppErrorRecord {
  const AppErrorRecord({required this.at, required this.source, required this.message, required this.stack});

  factory AppErrorRecord.fromJson(Map<String, Object?> json) {
    final rawSource = json['source'];
    final source = AppErrorSource.values.firstWhere(
      (v) => v.name == rawSource,
      orElse: () => AppErrorSource.flutter,
    );
    final parsedAt = json['at'] is String ? DateTime.tryParse(json['at']! as String) : null;
    return AppErrorRecord(
      at: parsedAt ?? DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
      source: source,
      message: json['message'] is String ? json['message']! as String : '',
      stack: json['stack'] is String ? json['stack']! as String : '',
    );
  }

  final DateTime at;
  final AppErrorSource source;
  final String message;
  final String stack;

  Map<String, Object?> toJson() => {
    'at': at.toUtc().toIso8601String(),
    'source': source.name,
    'message': message,
    'stack': stack,
  };
}

/// Records before delegating to the existing error handlers. Collection is
/// synchronous; when [attachFile] is called, redacted JSONL lines are also
/// persisted to disk so crashes survive an app restart.
class AppErrorLog {
  AppErrorLog({DateTime Function()? clock}) : _clock = clock ?? DateTime.now;

  static const maxRecords = 40;
  static const maxFileBytes = 256 * 1024;
  static const maxMessageLength = 4000;
  static const maxStackLength = 8000;
  static const _maxInputLength = 16000;

  final DateTime Function() _clock;
  final Queue<AppErrorRecord> _records = Queue();
  File? _file;
  bool _recording = false;

  /// Loads previously persisted records (if any) and persists subsequent ones.
  /// IO errors are swallowed so diagnostics never interfere with app startup.
  void attachFile(File file) {
    _file = file;
    try {
      if (!file.existsSync()) return;
      final existing = <AppErrorRecord>[];
      for (final line in file.readAsLinesSync()) {
        if (line.trim().isEmpty) continue;
        try {
          final decoded = jsonDecode(line);
          if (decoded is Map<String, Object?>) {
            existing.add(AppErrorRecord.fromJson(decoded));
          }
        } catch (_) {
          // Ignore malformed lines.
        }
      }
      final combined = [...existing, ..._records];
      final keep = combined.length > maxRecords ? combined.sublist(combined.length - maxRecords) : combined;
      _records
        ..clear()
        ..addAll(keep);
    } catch (_) {
      // Never fail startup on diagnostics IO.
    }
  }

  List<AppErrorRecord> snapshot() => List.unmodifiable(_records);

  void record(AppErrorSource source, Object error, StackTrace stack) {
    // An arbitrary error's toString can itself report an error. Keep that
    // nested report out of this collector while its original handler runs.
    if (_recording) return;
    _recording = true;
    try {
      final record = AppErrorRecord(
        at: _clock(),
        source: source,
        message: _text(error, maxMessageLength),
        stack: _text(stack, maxStackLength),
      );
      if (_records.length == maxRecords) _records.removeFirst();
      _records.addLast(record);
      _persist(record);
    } catch (_) {
      // Diagnostics must not replace the error being reported with its own.
    } finally {
      _recording = false;
    }
  }

  void _persist(AppErrorRecord record) {
    final file = _file;
    if (file == null) return;
    try {
      file.parent.createSync(recursive: true);
      if (file.existsSync() && file.lengthSync() > maxFileBytes) {
        final lines = _records.map((r) => jsonEncode(r.toJson())).join('\n');
        file.writeAsStringSync('$lines\n', flush: true);
      } else {
        file.writeAsStringSync('${jsonEncode(record.toJson())}\n', mode: FileMode.append, flush: true);
      }
    } catch (_) {
      // Ignore disk IO failures.
    }
  }

  static String _text(Object value, int limit) {
    String text;
    try {
      text = value.toString();
    } catch (_) {
      text = '<description unavailable>';
    }
    if (text.length > _maxInputLength) {
      final prefix = text.substring(0, _maxInputLength);
      final end = prefix.lastIndexOf('\n');
      // Do not retain a partial credential at the input boundary.
      text = '${end < 0 ? '' : prefix.substring(0, end + 1)}<text truncated>';
    }
    // A bounded prefix may omit a PEM block's END line. Mask even that
    // incomplete block before the shared diagnostics redactor runs.
    text = text.replaceAll(
      RegExp(r'-----BEGIN [^-\r\n]*PRIVATE KEY-----[\s\S]*?(?:-----END [^-\r\n]*PRIVATE KEY-----|$)'),
      '<private key redacted>',
    );
    // The shared redactor bounds lines before matching known key formats.
    // Omit oversized lines here so that shortening a key cannot prevent its
    // format from being recognised and leave a secret prefix in the record.
    text = text.split('\n').map((line) => line.length > maxLineLength ? '<long line omitted>' : line).join('\n');
    text = redactDiagnostics(text);
    return text.length > limit ? '${text.substring(0, limit - 1)}…' : text;
  }
}

/// Installs once, without introducing a new zone or suppressing the platform
/// embedding's fallback. Existing callback results and exceptions are kept.
class AppErrorCapture {
  AppErrorCapture(this.log);

  final AppErrorLog log;
  FlutterExceptionHandler? _previousFlutter;
  ErrorCallback? _previousPlatform;
  FlutterExceptionHandler? _flutter;
  ErrorCallback? _platform;

  void install() {
    if (_flutter != null) return;
    _previousFlutter = FlutterError.onError;
    _previousPlatform = PlatformDispatcher.instance.onError;
    _flutter = (details) {
      log.record(AppErrorSource.flutter, details.exception, details.stack ?? StackTrace.empty);
      _previousFlutter?.call(details);
    };
    _platform = (error, stack) {
      log.record(AppErrorSource.platform, error, stack);
      return _previousPlatform?.call(error, stack) ?? false;
    };
    FlutterError.onError = _flutter;
    PlatformDispatcher.instance.onError = _platform;
  }

  /// Restores only our own registrations; later integrations keep theirs.
  @visibleForTesting
  void uninstall() {
    if (_flutter == null) return;
    if (identical(FlutterError.onError, _flutter)) FlutterError.onError = _previousFlutter;
    if (identical(PlatformDispatcher.instance.onError, _platform)) {
      PlatformDispatcher.instance.onError = _previousPlatform;
    }
    _flutter = null;
    _platform = null;
  }
}

final appErrorLog = AppErrorLog();
final appErrorCapture = AppErrorCapture(appErrorLog);
