import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  testWidgets(
    'completed Codex Commentary remains visible while process history is folded',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final messages = [
        for (final item in [
          ('final', 'final_answer', 'Final response.', 3),
          ('commentary-2', 'commentary', 'Testing results.', 2),
          ('commentary-1', 'commentary', 'Inspecting files.', 1),
        ])
          ChatMessageModel(
            id: item.$1,
            type: 1,
            user: 2,
            content: {'text': item.$3},
            streamMeta: {
              'parentTaskId': 'codex-turn',
              'agentId': 'codex-acp',
              'seq': item.$4,
              'isFinal': true,
              'stopReason': 'end_turn',
              'agentMessagePhase': item.$2,
            },
          ),
      ];
      final group = buildAgentRunTimelineEntries(messages).single.group!;
      expect(group.isRunning, false);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
              child: AgentRunGroupMessage(
                group: group,
                useAcpPresentation: true,
                expanded: false,
                onToggleExpanded: () {},
                onBeforeTaskExecute: () async {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      for (final text in [
        'Inspecting files.',
        'Testing results.',
        'Final response.',
      ]) {
        final bubble = find.byWidgetPredicate(
          (widget) => widget is MessageBubble && widget.message.text == text,
        );
        expect(bubble, findsOneWidget);
        expect(tester.getSize(bubble).height, greaterThan(0));
      }
    },
  );
}
