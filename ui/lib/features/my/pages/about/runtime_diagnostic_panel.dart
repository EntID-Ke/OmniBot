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
      subtitle: Text(_active ? '采集中 · 已发送 $sent 次' : '临时发送真机运行日志'),
      children: [
        const Text(
          '开始后，每 10 秒向指定接收端发送经过凭证过滤的应用错误、'
          '崩溃、Agent／插件读取耗时及设备版本信息，20 分钟后自动停止。'
          '可离开此页复现问题，返回此处停止。应用进程退出后不会恢复采集。',
        ),
        const SizedBox(height: 12),
        if (!_active && _paired) ...[
          const SizedBox(height: 8),
          Text('连接已就绪：${Uri.tryParse(_endpoint.text)?.host ?? ''}'),
          const Text('点击“开始采集”即可连接，无需填写地址或诊断码。'),
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
          Text('剩余 ${_state['remainingSeconds'] ?? 0} 秒'),
          if (status != null) Text('发送失败：$status；稍后自动重试'),
          if (sent == 0 && status == null) const Text('正在连接接收端…'),
        ],
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
