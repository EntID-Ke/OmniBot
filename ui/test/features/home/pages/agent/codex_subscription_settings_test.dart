import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/codex_subscription_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const events = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  Future<void> pump(WidgetTester tester) async {
    messenger.setMockMethodCallHandler(events, (_) async => null);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: CodexSubscriptionSettings(
              agentId: 'codex-acp',
              authRevision: 0,
              onConfigurationChanged: (_) {},
            ),
          ),
        ),
      ),
    );
  }

  testWidgets(
    'subscription model changes persist and reload dependent effort',
    (tester) async {
      var selected = 'official-a';
      final writes = <Map>[];
      final refreshes = <Map>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'model/list') {
          refreshes.add(call.arguments as Map);
          return {
            'models': [
              {'id': 'official-a', 'displayName': 'Official A'},
              {'id': 'official-b', 'displayName': 'Official B'},
            ],
            'currentModelId': selected,
            'reasoningEfforts': ['low', 'high'],
            'currentReasoningEffort': 'high',
          };
        }
        if (call.method == 'agent/config/write') {
          writes.add(call.arguments as Map);
          selected = call.arguments['model'] as String;
          return {'model': selected, 'reasoningEffort': null};
        }
        return {};
      });
      await pump(tester);
      await tester.tap(find.text('Refresh official model catalog'));
      await tester.pumpAndSettle();
      expect(refreshes.single['refreshConfig'], true);
      await tester.tap(find.text('Official A'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Official B').last);
      await tester.pumpAndSettle();
      expect(writes.single['model'], 'official-b');
      expect(writes.single['authMode'], 'chatgpt');
      expect(writes.single['expectedAuthRevision'], 0);
      final field = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const ValueKey('codex-subscription-model-official-b')),
      );
      expect(field.initialValue, 'official-b');
      expect(
        find.byKey(const ValueKey('codex-subscription-effort-high')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('unsupported adapters fail before requesting authentication', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'authMethods': <Object>[]};
    });
    await pump(tester);
    await tester.tap(find.text('Sign in / Reauthenticate'));
    await tester.pumpAndSettle();
    expect(find.textContaining('does not support device-code'), findsOneWidget);
    expect(calls.map((call) => call.method), ['initialize']);
    expect(calls.single.arguments['localRuntime'], true);
  });

  testWidgets(
    'device code is shown and cancellation uses the owning ACP request',
    (tester) async {
      final login = Completer<Map<String, dynamic>>();
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'initialize') {
          return {
            'authMethods': [
              {'id': 'chat-gpt-device-code'},
            ],
          };
        }
        if (call.method == 'authenticate') return login.future;
        if (call.method == 'respondToServerRequest') {
          if (!login.isCompleted) {
            login.completeError(PlatformException(code: 'cancelled'));
          }
          return {'ok': true};
        }
        return {};
      });
      await pump(tester);
      await tester.tap(find.text('Sign in / Reauthenticate'));
      await tester.pump();
      await messenger.handlePlatformMessage(
        'cn.com.omnimind.bot/AgentRuntimeEvents',
        const StandardMethodCodec().encodeSuccessEnvelope({
          'agentId': 'codex-acp',
          'id': 'owned-request',
          'message': {
            'method': 'elicitation/create',
            'id': 'owned-request',
            'params': {
              'mode': 'url',
              'url': 'https://auth.openai.com/codex/device',
              'message': 'Sign in to ChatGPT and enter this code: ABCD-EFGH',
            },
          },
        }),
        (_) {},
      );
      await tester.pump();
      expect(find.textContaining('ABCD-EFGH'), findsOneWidget);
      await tester.tap(find.text('Cancel sign-in'));
      await tester.pumpAndSettle();
      final cancel = calls.firstWhere(
        (call) => call.method == 'respondToServerRequest',
      );
      expect(cancel.arguments, {
        'requestId': 'owned-request',
        'agentId': 'codex-acp',
        'response': {'action': 'cancel'},
      });
      expect(
        calls.where((call) => call.method == 'authenticate'),
        hasLength(1),
      );
      expect(find.text('Cancel sign-in'), findsNothing);
    },
  );

  testWidgets(
    'account status and models come from the selected local runtime',
    (tester) async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        switch (call.method) {
          case 'authentication/status':
            return {'type': 'chat-gpt', 'email': 'fixture@example.test'};
          case 'model/list':
            return {
              'models': [
                {'id': 'fixture-model', 'displayName': 'Fixture Model'},
              ],
              'currentModelId': 'fixture-model',
              'reasoningEfforts': ['high'],
            };
          default:
            return {};
        }
      });
      await pump(tester);
      await tester.tap(find.text('Refresh sign-in status'));
      await tester.pumpAndSettle();
      expect(find.text('Signed in: fixture@example.test'), findsOneWidget);
      await tester.tap(find.text('Refresh official model catalog'));
      await tester.pumpAndSettle();
      expect(find.text('Fixture Model'), findsOneWidget);
      for (final call in calls) {
        expect(call.arguments['agentId'], 'codex-acp');
        expect(call.arguments['localRuntime'], true);
      }
    },
  );
}
