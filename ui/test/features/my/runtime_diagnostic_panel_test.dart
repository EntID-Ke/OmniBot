import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/my/pages/about/runtime_diagnostic_panel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets('private link fills connection without automatic collection or typing', (tester) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'consumeRuntimeDiagnosticLink') {
        return {'endpoint': 'https://receiver.example/v1/runtime-logs', 'code': 'a' * 43};
      }
      return {'active': call.method == 'startRuntimeDiagnostics', 'sentCount': 0};
    });
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: RuntimeDiagnosticPanel()))));
    await tester.pumpAndSettle();
    expect(find.text('连接已就绪：receiver.example'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(calls.where((call) => call.method == 'startRuntimeDiagnostics'), isEmpty);
    await tester.tap(find.text('开始采集'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((call) => call.method == 'startRuntimeDiagnostics').arguments, {
      'endpoint': 'https://receiver.example/v1/runtime-logs', 'code': 'a' * 43,
    });
    await tester.tap(find.text('停止采集'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开始采集'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'startRuntimeDiagnostics').length, 2);
    expect(calls.lastWhere((call) => call.method == 'startRuntimeDiagnostics').arguments['code'], 'a' * 43);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'starts explicit capture and leaves it active when navigating away',
    (tester) async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'active': call.method == 'startRuntimeDiagnostics',
          'sentCount': 0,
        };
      });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: RuntimeDiagnosticPanel()),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.text('联网诊断'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextField).at(0),
        ' https://receiver.example/v1/runtime-logs ',
      );
      await tester.enterText(
        find.byType(TextField).at(1),
        ' fixture-code-32-characters-minimum ',
      );
      await tester.tap(find.text('开始采集'));
      await tester.pumpAndSettle();
      expect(find.text('停止采集'), findsOneWidget);
      expect(find.text('正在连接接收端…'), findsOneWidget);
      expect(
        calls
            .singleWhere((call) => call.method == 'startRuntimeDiagnostics')
            .arguments,
        {
          'endpoint': 'https://receiver.example/v1/runtime-logs',
          'code': 'fixture-code-32-characters-minimum',
        },
      );
      await tester.pumpWidget(const SizedBox.shrink());
      expect(
        calls.where((call) => call.method == 'stopRuntimeDiagnostics'),
        isEmpty,
      );
    },
  );

  testWidgets('late state read cannot undo start and user can stop capture', (
    tester,
  ) async {
    final initial = Completer<Map<String, dynamic>>();
    var stopped = false;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'runtimeDiagnosticsState') return initial.future;
      if (call.method == 'stopRuntimeDiagnostics') stopped = true;
      return {
        'active': call.method == 'startRuntimeDiagnostics',
        'sentCount': 0,
      };
    });
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: RuntimeDiagnosticPanel()),
        ),
      ),
    );
    await tester.tap(find.text('联网诊断'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开始采集'));
    await tester.pumpAndSettle();
    initial.complete({'active': false});
    await tester.pumpAndSettle();
    expect(find.text('停止采集'), findsOneWidget);
    await tester.tap(find.text('停止采集'));
    await tester.pumpAndSettle();
    expect(stopped, isTrue);
    expect(find.text('开始采集'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
