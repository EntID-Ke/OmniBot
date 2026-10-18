import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/runtime_log_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'framework errors preserve the handler and omit exception bodies',
    () async {
      final original = FlutterError.onError;
      addTearDown(() => FlutterError.onError = original);
      final calls = <MethodCall>[];
      FlutterErrorDetails? forwarded;
      FlutterError.onError = (details) => forwarded = details;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return true;
      });
      final restore = RuntimeLogService.installFlutterErrorReporting();
      addTearDown(restore);
      final details = FlutterErrorDetails(
        exception: StateError('Bearer fake-private-value'),
        stack: StackTrace.fromString('#0 render (package:ui/example.dart:1:2)'),
      );
      FlutterError.onError!(details);
      await Future<void>.delayed(Duration.zero);
      expect(forwarded, same(details));
      expect(calls.single.method, 'recordFlutterRuntimeError');
      expect(calls.single.arguments, {
        'source': 'framework',
        'errorType': 'StateError',
        'stackTrace': '#0 render (package:ui/example.dart:1:2)',
      });
      expect(
        calls.single.arguments.toString(),
        isNot(contains('fake-private-value')),
      );
    },
  );

  test(
    'async errors preserve handled status even when reporting fails',
    () async {
      final original = PlatformDispatcher.instance.onError;
      addTearDown(() => PlatformDispatcher.instance.onError = original);
      final error = StateError('do not persist this body');
      Object? forwarded;
      PlatformDispatcher.instance.onError = (value, stack) {
        forwarded = value;
        return true;
      };
      messenger.setMockMethodCallHandler(channel, (_) async {
        throw PlatformException(code: 'LOG_UNAVAILABLE');
      });
      final restore = RuntimeLogService.installFlutterErrorReporting();
      addTearDown(restore);
      expect(
        PlatformDispatcher.instance.onError!(error, StackTrace.current),
        isTrue,
      );
      await Future<void>.delayed(Duration.zero);
      expect(forwarded, same(error));
    },
  );

  test('without an async handler errors remain unhandled', () async {
    final original = PlatformDispatcher.instance.onError;
    addTearDown(() => PlatformDispatcher.instance.onError = original);
    PlatformDispatcher.instance.onError = null;
    final restore = RuntimeLogService.installFlutterErrorReporting();
    addTearDown(restore);
    expect(
      PlatformDispatcher.instance.onError!(
        StateError('body'),
        StackTrace.current,
      ),
      isFalse,
    );
    await Future<void>.delayed(Duration.zero);
  });
}
