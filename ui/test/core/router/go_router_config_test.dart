import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_config.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    GoRouterManager.setInitialRoute(null);
    GoRouterManager.setSubEngine(false);
  });

  tearDown(() {
    GoRouterManager.setInitialRoute(null);
    GoRouterManager.setSubEngine(false);
  });

  testWidgets(
    'settings opens Agent mode while optional plugin actions are pending',
    (tester) async {
      await StorageService.setBool(StorageKeys.welcomeCompleted, true);
      GoRouterManager.setInitialRoute('/home/settings');
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(800, 1600);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      const agent = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
      const plugins = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
      final actions = Completer<List<dynamic>>();
      final calls = <String>[];
      messenger.setMockMethodCallHandler(agent, (call) async {
        calls.add(call.method);
        if (call.method != 'agent/list') return {};
        return {
          'selectedAgentId': 'codex-acp',
          'agents': [
            {
              'id': 'codex-acp',
              'name': 'Codex',
              'command': 'codex-acp',
              'enabled': true,
              'builtIn': true,
              'status': 'unchecked',
            },
          ],
        };
      });
      messenger.setMockMethodCallHandler(plugins, (call) async {
        if (call.method == 'listActions') return actions.future;
        return [];
      });
      addTearDown(() {
        messenger.setMockMethodCallHandler(agent, null);
        messenger.setMockMethodCallHandler(plugins, null);
        if (!actions.isCompleted) actions.complete([]);
      });
      GoRouter? router;
      await tester.pumpWidget(
        ProviderScope(
          child: Consumer(
            builder: (context, ref, child) {
              router ??= GoRouterManager.createRouter(ref);
              return MaterialApp.router(
                routerConfig: router,
                theme: AppTheme.lightTheme,
                locale: const Locale('zh'),
                localizationsDelegates: AppLocalizations.localizationsDelegates,
                supportedLocales: AppLocalizations.supportedLocales,
              );
            },
          ),
        ),
      );
      addTearDown(() => router?.dispose());
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Agent 模式'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('Agent 模式'));
      await tester.pumpAndSettle();
      expect(find.byType(AgentModeSettingPage), findsOneWidget);
      expect(find.text('Codex'), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(calls, contains('agent/list'));
      expect(calls, isNot(contains('agent/refresh')));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  test('router config includes the main application routes', () {
    final routeNames = AppRouterConfig.getAllRoutes()
        .map((route) => route.name)
        .whereType<String>()
        .toSet();

    expect(routeNames, contains('home/chat'));
    expect(routeNames, contains('welcome/choice'));
    expect(routeNames, contains('task/omniflow'));
    expect(routeNames, contains('task/run_logs'));
    expect(routeNames, contains('home/first_use_tutorial/setup'));
    expect(routeNames, isNot(contains('home/first_use_tutorial')));
    expect(routeNames, isNot(contains('home/first_use_tutorial/features')));
    expect(routeNames, isNot(contains('home/first_use_tutorial/plugins')));
  });

  testWidgets('fresh launch starts in the first-use tutorial', (tester) async {
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/welcome/choice');
  });

  testWidgets('completed onboarding starts in chat', (tester) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/home/chat');
  });

  testWidgets('sub-engine launch bypasses the onboarding guard', (
    tester,
  ) async {
    GoRouterManager.setSubEngine(true);
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/home/chat');
  });
}
