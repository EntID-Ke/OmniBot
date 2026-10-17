import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:ui/services/agent_runtime_service.dart';

/// Account interaction uses the existing ACP authenticate/elicitation surface.
/// No token file or model request implementation lives in Flutter.
class CodexSubscriptionSettings extends StatefulWidget {
  const CodexSubscriptionSettings({
    super.key,
    required this.agentId,
    required this.authRevision,
    required this.onConfigurationChanged,
  });

  final String agentId;
  final int authRevision;
  final ValueChanged<Map<String, dynamic>> onConfigurationChanged;

  @override
  State<CodexSubscriptionSettings> createState() =>
      _CodexSubscriptionSettingsState();
}

class _CodexSubscriptionSettingsState extends State<CodexSubscriptionSettings> {
  StreamSubscription<Map<String, dynamic>>? _events;
  Object? _requestId;
  bool _busy = false;
  bool _signingIn = false;
  String? _message;
  Uri? _verificationUrl;
  String? _error;
  Map<String, dynamic> _account = const {};
  List<Map<String, dynamic>> _models = const [];
  List<String> _efforts = const [];
  String? _model;
  String? _effort;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';
  String _text(String zh, String en) => _english ? en : zh;

  @override
  void dispose() {
    _events?.cancel();
    final requestId = _requestId;
    if (requestId != null) {
      unawaited(
        AgentRuntimeService.cancelElicitation(
          requestId: requestId,
          agentId: widget.agentId,
        ),
      );
    }
    super.dispose();
  }

  void _handleEvent(Map<String, dynamic> event) {
    if (event['agentId'] != widget.agentId) return;
    final message = event['message'] is Map
        ? Map<String, dynamic>.from(event['message'] as Map)
        : event;
    if (message['method'] == '_auth/status_update') {
      final params = message['params'];
      final status = params is Map ? params['authStatus'] : null;
      if (mounted && status is Map && status['kind'] == 'none') {
        setState(() => _account = const {'type': 'unauthenticated'});
      }
      if (mounted && status is Map && status['kind'] == 'account') {
        final account = status['account'];
        setState(
          () => _account = {
            'type': 'chat-gpt',
            if (account is Map) ...{
              for (final key in ['email', 'organization', 'plan'])
                if (account[key] != null) key: account[key],
            },
          },
        );
      }
      return;
    }
    if (!_signingIn || message['method'] != 'elicitation/create') return;
    final params = message['params'] is Map
        ? Map<String, dynamic>.from(message['params'] as Map)
        : <String, dynamic>{};
    if (params['mode'] != 'url' || params['sessionId'] != null) return;
    final id = message['id'] ?? event['id'];
    final url = Uri.tryParse(params['url']?.toString() ?? '');
    if (id == null) return;
    if (url == null ||
        url.scheme != 'https' ||
        !{'auth.openai.com', 'chatgpt.com'}.contains(url.host) ||
        url.userInfo.isNotEmpty) {
      unawaited(
        AgentRuntimeService.cancelElicitation(
          requestId: id,
          agentId: widget.agentId,
        ),
      );
      if (mounted) {
        setState(
          () => _error = _text(
            '运行时返回了无法验证的授权地址。',
            'The runtime returned an unsupported authorization URL.',
          ),
        );
      }
      return;
    }
    if (!mounted) return;
    setState(() {
      _requestId = id;
      _message = params['message']?.toString();
      _verificationUrl = url;
    });
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshAccount() async {
    _events ??= AgentRuntimeService.events.listen(_handleEvent);
    await AgentRuntimeService.initialize(
      agentId: widget.agentId,
      localRuntime: true,
    );
    final account = await AgentRuntimeService.readAgentAuthenticationStatus(
      widget.agentId,
    );
    if (mounted)
      setState(
        () => _account = {
          ...account,
          if (_account['email'] == account['email'] && _account['plan'] != null)
            'plan': _account['plan'],
        },
      );
  }

  Future<void> _signIn() => _run(() async {
    _signingIn = true;
    try {
      final initialized = await AgentRuntimeService.initialize(
        agentId: widget.agentId,
        localRuntime: true,
      );
      final methods = (initialized['authMethods'] as List? ?? const []);
      if (!methods.any(
        (method) => method is Map && method['id'] == 'chat-gpt-device-code',
      )) {
        throw StateError(
          _text(
            '已安装的适配器不支持设备码登录，请重新安装 Codex。',
            'The installed adapter does not support device-code login. Reinstall Codex.',
          ),
        );
      }
      _events ??= AgentRuntimeService.events.listen(_handleEvent);
      await AgentRuntimeService.authenticateAgent(
        agentId: widget.agentId,
        methodId: 'chat-gpt-device-code',
        localRuntime: true,
      ).timeout(const Duration(minutes: 15));
      await _refreshAccount();
    } finally {
      final id = _requestId;
      _requestId = null;
      if (id != null) {
        // Settle the request-scoped prompt even if upstream completes login
        // before the user answers its URL elicitation.
        await AgentRuntimeService.cancelElicitation(
          requestId: id,
          agentId: widget.agentId,
        );
      }
      _signingIn = false;
      if (mounted) {
        setState(() {
          _message = null;
          _verificationUrl = null;
        });
      }
    }
  });

  Future<void> _refreshModels() => _run(_loadModels);

  Future<void> _loadModels() async {
    final catalog = await AgentRuntimeService.listModels(
      agentId: widget.agentId,
      localRuntime: true,
    );
    final models = (catalog['models'] as List? ?? const [])
        .whereType<Map>()
        .map((model) => Map<String, dynamic>.from(model))
        .toList();
    if (models.isEmpty) {
      throw StateError(
        _text(
          '官方运行时未返回可用模型，请检查登录和网络。',
          'The official runtime returned no models. Check sign-in and network access.',
        ),
      );
    }
    if (!mounted) return;
    setState(() {
      _models = models;
      _efforts = (catalog['reasoningEfforts'] as List? ?? const [])
          .whereType<String>()
          .toList();
      _model = catalog['currentModelId']?.toString();
      _effort = catalog['currentReasoningEffort']?.toString();
    });
  }

  Future<void> _saveModel(String? model, String? effort) => _run(() async {
    await AgentRuntimeService.initialize(
      agentId: widget.agentId,
      localRuntime: true,
    );
    final payload = await AgentRuntimeService.writeAgentConfig(
      widget.agentId,
      authMode: 'chatgpt',
      expectedAuthRevision: widget.authRevision,
      model: model,
      reasoningEffort: effort,
    );
    widget.onConfigurationChanged(payload);
    await _loadModels();
    if (mounted) {
      setState(() {
        _model = payload['model']?.toString();
        _effort = payload['reasoningEffort']?.toString();
      });
    }
  });

  @override
  Widget build(BuildContext context) {
    final signedIn = _account['type'] == 'chat-gpt';
    final deviceCode = _message?.split('code: ').last;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          signedIn
              ? _text(
                  '已登录：${_account['email'] ?? ''}',
                  'Signed in: ${_account['email'] ?? ''}',
                )
              : _text(
                  '使用 ChatGPT 账号登录，无需 API Key。',
                  'Sign in with your ChatGPT account. No API key is required.',
                ),
        ),
        if (signedIn && _account['plan'] != null)
          Text(_text('订阅：${_account['plan']}', 'Plan: ${_account['plan']}')),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton(
              onPressed: _busy ? null : _signIn,
              child: Text(_text('登录 / 重新认证', 'Sign in / Reauthenticate')),
            ),
            TextButton(
              onPressed: _busy ? null : () => _run(_refreshAccount),
              child: Text(_text('刷新登录状态', 'Refresh sign-in status')),
            ),
            TextButton(
              onPressed: _busy
                  ? null
                  : () => _run(() async {
                      await AgentRuntimeService.initialize(
                        agentId: widget.agentId,
                        localRuntime: true,
                      );
                      await AgentRuntimeService.logoutAgent(
                        agentId: widget.agentId,
                        localRuntime: true,
                      );
                      if (mounted) {
                        setState(() {
                          _models = const [];
                          _account = const {};
                        });
                      }
                      await _refreshAccount();
                    }),
              child: Text(_text('退出登录', 'Sign out')),
            ),
          ],
        ),
        if (_busy)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: LinearProgressIndicator(),
          ),
        if (_message != null) ...[
          SelectableText(_message!),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => Clipboard.setData(
                  ClipboardData(text: deviceCode ?? _message!),
                ),
                child: Text(_text('复制设备码', 'Copy device code')),
              ),
              TextButton(
                onPressed: () async {
                  final url = _verificationUrl;
                  if (url != null &&
                      !await launchUrl(
                        url,
                        mode: LaunchMode.externalApplication,
                      ) &&
                      mounted) {
                    setState(
                      () => _error = _text(
                        '无法打开浏览器，请复制授权地址。',
                        'Could not open the browser. Copy the verification URL.',
                      ),
                    );
                  }
                },
                child: Text(
                  _text('打开官方授权页面', 'Open official verification page'),
                ),
              ),
              TextButton(
                onPressed: () async {
                  final id = _requestId;
                  if (id != null) {
                    await AgentRuntimeService.cancelElicitation(
                      requestId: id,
                      agentId: widget.agentId,
                    );
                  }
                },
                child: Text(_text('取消授权', 'Cancel sign-in')),
              ),
            ],
          ),
          if (_verificationUrl != null)
            SelectableText(_verificationUrl.toString()),
        ],
        TextButton(
          onPressed: _busy ? null : _refreshModels,
          child: Text(_text('刷新官方模型目录', 'Refresh official model catalog')),
        ),
        if (_models.isNotEmpty)
          DropdownButtonFormField<String>(
            key: ValueKey('codex-subscription-model-$_model'),
            initialValue: _models.any((model) => model['id'] == _model)
                ? _model
                : null,
            decoration: InputDecoration(
              labelText: _text('默认模型', 'Default model'),
            ),
            isExpanded: true,
            items: _models
                .map(
                  (model) => DropdownMenuItem(
                    value: model['id']?.toString(),
                    child: Text(
                      model['displayName']?.toString() ??
                          model['id'].toString(),
                    ),
                  ),
                )
                .toList(),
            onChanged: _busy ? null : (value) => _saveModel(value, ''),
          ),
        if (_efforts.isNotEmpty)
          DropdownButtonFormField<String>(
            key: ValueKey('codex-subscription-effort-$_effort'),
            initialValue: _efforts.contains(_effort) ? _effort : null,
            decoration: InputDecoration(
              labelText: _text('推理强度', 'Reasoning effort'),
            ),
            items: _efforts
                .map(
                  (effort) =>
                      DropdownMenuItem(value: effort, child: Text(effort)),
                )
                .toList(),
            onChanged: _busy ? null : (value) => _saveModel(null, value),
          ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}
