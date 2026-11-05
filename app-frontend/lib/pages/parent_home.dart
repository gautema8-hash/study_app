import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 家长首页：统一视觉 + 顶部单选身份切换 + 左上角明确家长入口。
/// 会员状态与 DIY 配置读取/保存逻辑保持原样。
class ParentHome extends StatefulWidget {
  const ParentHome({super.key});
  @override
  State<ParentHome> createState() => _ParentHomeState();
}

class _ParentHomeState extends State<ParentHome> {
  Map<String, dynamic>? member;
  Map<String, dynamic>? config;
  bool? hasPassword;
  double threshold = 80;
  String? tip;
  bool busy = false;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final m = await Api.get('/api/member/status');
      final c = await Api.get('/api/bank/config');
      final p = await Api.get('/api/auth/password/parent/status');
      setState(() {
        member = m; config = c; hasPassword = p['hasPassword'] == true;
        threshold = ((c['threshold'] as num?) ?? 80).toDouble();
      });
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    }
  }

  Future<void> _saveThreshold() async {
    setState(() { busy = true; tip = null; });
    try {
      final c = Map<String, dynamic>.from(config ?? {});
      await Api.put('/api/bank/config', body: {
        'count': c['count'], 'durationMin': c['durationMin'],
        'threshold': threshold.round(), 'sessionMinutes': c['sessionMinutes'],
        'dailyLimit': c['dailyLimit'],
      });
      setState(() => tip = '配置已保存（门槛 $threshold 分）');
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    } finally {
      setState(() => busy = false);
    }
  }

  String get tierName => switch (member?['tier'] as int? ?? 0) { 1 => 'VIP', 2 => 'SVIP', _ => '未开通' };

  @override
  Widget build(BuildContext context) {
    final expire = member?['expireAt']?.toString() ?? '-';
    return Scaffold(
      backgroundColor: Ui.bg,
      appBar: uiAppBar(
        title: '家长端',
        leading: uiParentAccessButton(context, UiIdentity.parent),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), tooltip: '刷新',
              onPressed: _load),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(64),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
            child: UiIdentitySwitcher(current: UiIdentity.parent, onRequestChange: (t) {
              if (t != UiIdentity.parent) {
                openParentAccess(context, current: UiIdentity.parent, target: t);
              }
            }),
          ),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(20), children: [
          if (tip != null) ...[UiBanner(message: tip!, tone: UiTone.info), const SizedBox(height: 16)],
          UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('会员状态', style: Ui.title),
              const Spacer(),
              Container(width: 22, height: 2, decoration: BoxDecoration(color: Ui.gold, borderRadius: BorderRadius.circular(1))),
            ]),
            const SizedBox(height: 14),
            Row(children: [
              _stat('当前档位', tierName),
              _stat('到期时间', expire.startsWith('9999') ? '永久' : expire),
            ]),
          ])),
          const SizedBox(height: 16),
          UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('解锁规则 · DIY 配置', style: Ui.title),
            const SizedBox(height: 12),
            Text('每次题数：${config?['count'] ?? '-'} 题　时长：${config?['durationMin'] ?? '-'} 分钟', style: Ui.body),
            Text('会话有效期：${config?['sessionMinutes'] ?? '-'} 分钟　每日挑战：${config?['dailyLimit'] ?? '-'} 次', style: Ui.body),
            const SizedBox(height: 8),
            Slider(value: threshold, min: 60, max: 100, divisions: 40,
                label: '${threshold.round()} 分', activeColor: Ui.primary,
                onChanged: (v) => setState(() => threshold = v)),
            Center(child: UiButton(label: '保存分数门槛', onPressed: busy ? null : _saveThreshold,
                loading: busy, width: 220)),
          ])),
          const SizedBox(height: 16),
          UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('家长操作密码', style: Ui.title),
            const SizedBox(height: 8),
            Text(hasPassword == true
                ? '已设置：切换身份、退出登录时需验证（可在家长入口中修改）'
                : '尚未设置：请在家长入口中设置 6 位数字密码，用于身份切换与退出校验',
                style: Ui.caption),
            const SizedBox(height: 12),
            UiButton(label: hasPassword == true ? '修改操作密码' : '设置操作密码',
                primary: false, onPressed: () async {
                  await openParentAccess(context, current: UiIdentity.parent);
                  _load();
                }, icon: Icons.password_rounded),
          ])),
          const SizedBox(height: 24),
        ]),
      ),
    );
  }

  Widget _stat(String label, String value) => Expanded(child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: Ui.caption),
        const SizedBox(height: 4),
        Text(value, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: Ui.ink)),
      ]));
}
