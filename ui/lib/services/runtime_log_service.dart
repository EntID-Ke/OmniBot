import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class RuntimeLogEntry {
  final String id;
  final DateTime createdAt;
  final String level;
  final String tag;
  final String message;
  final String? stackTrace;
  final bool isCrash;

  const RuntimeLogEntry({
    required this.id,
    required this.createdAt,
    required this.level,
    required this.tag,
    required this.message,
    this.stackTrace,
    required this.isCrash,
  });

  factory RuntimeLogEntry.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final createdAtValue = raw['createdAt'];
    final createdAtMillis = createdAtValue is int
        ? createdAtValue
        : int.tryParse(createdAtValue?.toString() ?? '') ?? 0;
    return RuntimeLogEntry(
      id: (raw['id'] ?? '').toString(),
      createdAt: DateTime.fromMillisecondsSinceEpoch(createdAtMillis),
      level: (raw['level'] ?? 'INFO').toString(),
      tag: (raw['tag'] ?? '').toString(),
      message: (raw['message'] ?? '').toString(),
      stackTrace: raw['stackTrace']?.toString(),
      isCrash: raw['isCrash'] == true,
    );
  }

  String get displayTitle {
    final tagPart = tag.isNotEmpty ? '[$tag] ' : '';
    return '$tagPart$message';
  }
}

class RuntimeLogService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  /// Mirrors error locations without sending exception bodies, which can
  /// contain provider responses, request data, or credentials.
  static VoidCallback installFlutterErrorReporting() {
    final previousFrameworkHandler = FlutterError.onError;
    final previousAsyncHandler = PlatformDispatcher.instance.onError;
    FlutterError.onError = (details) {
      unawaited(
        _reportFlutterError('framework', details.exception, details.stack),
      );
      previousFrameworkHandler?.call(details);
    };
    PlatformDispatcher.instance.onError = (error, stack) {
      unawaited(_reportFlutterError('async', error, stack));
      return previousAsyncHandler?.call(error, stack) ?? false;
    };
    return () {
      FlutterError.onError = previousFrameworkHandler;
      PlatformDispatcher.instance.onError = previousAsyncHandler;
    };
  }

  static Future<void> _reportFlutterError(
    String source,
    Object error,
    StackTrace? stack,
  ) async {
    try {
      await _assistCore.invokeMethod('recordFlutterRuntimeError', {
        'source': source,
        'errorType': error.runtimeType.toString(),
        'stackTrace': stack?.toString(),
      });
    } catch (_) {
      // Reporting must never replace the original error or recurse when the
      // native channel is unavailable during startup/shutdown.
    }
  }

  static Future<List<RuntimeLogEntry>> listRecent({int limit = 100}) async {
    final result = await _assistCore.invokeMethod<List<dynamic>>(
      'listRuntimeLogs',
      {'limit': limit},
    );
    return (result ?? const [])
        .whereType<Map>()
        .map((item) => RuntimeLogEntry.fromMap(item))
        .toList();
  }

  static Future<void> clear() async {
    await _assistCore.invokeMethod('clearRuntimeLogs');
  }
}
