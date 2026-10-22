import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/runtime_log_service.dart';

/// Lives on the existing log page; collection continues while reproducing a bug.
class RuntimeDiagnosticPanel extends StatefulWidget {
  const RuntimeDiagnosticPanel({super.key});

  @override
  State<RuntimeDiagnosticPanel> createState() => _RuntimeDiagnosticPanelState();
}

class _RuntimeDiagnosticPanelState extends State<RuntimeDiagnosticPanel> {
  final _endpoint = TextEditingController();
  final _code = TextEditingController();
  Map<dynamic, dynamic> _state = const {};
  Timer? _timer;
  bool _busy = false;
  bool _refreshing = false;
  int _revision = 0;
  String? _error;
  bool _paired = false;

  bool get _active => _state['active'] == true;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    unawaited(_loadConnectionLink());
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  Future<void> _loadConnectionLink() async {
    try {
      final link = await RuntimeLogService.consumeDiagnosticLink();
      if (!mounted ||
          link == null ||
          link['endpoint'] is! String ||
          link['code'] is! String)
        return;
      setState(() {
        _endpoint.text = link['endpoint']?.toString() ?? '';
        _code.text = link['code']?.toString() ?? '';
        _paired = true;
      });
    } catch (_) {
      // Manual setup stays available on older native hosts.
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _endpoint.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_refreshing || _busy) return;
    _refreshing = true;
    final revision = _revision;
    try {
      final state = await RuntimeLogService.diagnosticsState().timeout(
        const Duration(seconds: 10),
      );
      if (mounted && revision == _revision) setState(() => _state = state);
    } catch (_) {
      // A later poll can recover; starting/stopping reports actionable errors.
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _toggle() async {
    _revision++;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final state = _active
          ? await RuntimeLogService.stopDiagnostics()
          : await RuntimeLogService.startDiagnostics(
              _endpoint.text,
              _code.text,
            );
      if (!mounted) return;
      setState(() => _state = state);
      if (_active && !_paired) _code.clear();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() => _error = error.message ?? '日志诊断操作失败，请重试');
      }
    } catch (_) {
      if (mounted) setState(() => _error = '日志诊断操作失败，请重试');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _state['error'];
    final sent = _state['sentCount'] ?? 0;
    return ExpansionTile(
      key: ValueKey('runtime-diagnostics-$_paired'),
      initiallyExpanded: _paired,
      tilePadding: EdgeInsets.zero,
      title: const Text('联网诊断'),
      subtitle: Text(_active ? '自动上报中 · 已发送 $sent 批' : '连接后自动发送应用运行日志'),
      children: [
        const Text(
          '连接一次后自动上报本应用全部级别的运行日志、崩溃和 Agent／插件诊断，'
          '重开应用自动恢复。凭证先过滤；断网暂存，连接恢复后续传，接收端保留新增记录。'
          '离线队列最多 32 MiB／16384 条，超出时显示丢弃数。可在此停止。',
        ),
        const SizedBox(height: 12),
        if (!_active && _paired) ...[
          const SizedBox(height: 8),
          Text('连接已就绪：${Uri.tryParse(_endpoint.text)?.host ?? ''}'),
          const Text('连接链接会自动启用上报；也可点击下方按钮重试。'),
        ] else if (!_active) ...[
          TextField(
            controller: _endpoint,
            enabled: !_busy,
            decoration: const InputDecoration(labelText: '接收地址或诊断链接'),
            keyboardType: TextInputType.url,
            autocorrect: false,
            enableSuggestions: false,
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _code,
            enabled: !_busy,
            decoration: const InputDecoration(
              labelText: '临时诊断码',
              helperText: '使用完整诊断链接时无需填写',
            ),
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
          ),
        ] else ...[
          const SizedBox(height: 8),
          Text('接收端：${_state['receiverHost'] ?? ''}'),
          Text(
            '待发送 ${_state['pendingLogs'] ?? 0} 条 · 丢弃 ${_state['droppedLogs'] ?? 0} 条',
          ),
          if (_state['captureError'] != null)
            Text('采集异常：${_state['captureError']}；稍后自动重试'),
          if (sent == 0 && status == null) const Text('正在连接接收端…'),
        ],
        if (status != null) Text('诊断异常：$status；采集中会自动重试，可重新导入连接'),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton(
            onPressed: _busy ? null : _toggle,
            child: Text(_busy ? '请稍候…' : (_active ? '停止采集' : '开始采集')),
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }
}
