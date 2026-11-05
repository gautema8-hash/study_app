import 'package:flutter/material.dart';
import '../core/api.dart';

/// 家长首页：会员状态 + DIY 配置调整（FR-08）+ 退出
class ParentHome extends StatefulWidget {
  const ParentHome({super.key});
  @override
  State<ParentHome> createState() => _ParentHomeState();
}

class _ParentHomeState extends State<ParentHome> {
  Map<String, dynamic>? member;
  Map<String, dynamic>? config;
  double threshold = 80;
  String? tip;
  bool busy = false;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final m = await Api.get('/api/member/status');
      final c = await Api.get('/api/bank/config');
      setState(() {
        member = m; config = c;
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
      appBar: AppBar(title: const Text('家长端'), actions: [
        IconButton(icon: const Icon(Icons.logout), onPressed: () => Navigator.pushReplacementNamed(context, '/')),
      ]),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Card(child: ListTile(
            title: Text('会员：$tierName'),
            subtitle: Text('到期：$expire\n题库与会员购买请使用管理后台（admin-web）'),
            isThreeLine: true,
          )),
          const SizedBox(height: 16),
          Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('解锁规则（DIY 配置，FR-08）', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('每次题数：${config?['count'] ?? '-'} 题　时长：${config?['durationMin'] ?? '-'} 分钟'),
            Text('会话有效期：${config?['sessionMinutes'] ?? '-'} 分钟　每日挑战：${config?['dailyLimit'] ?? '-'} 次'),
            Slider(value: threshold, min: 60, max: 100, divisions: 40, label: '${threshold.round()} 分',
                onChanged: (v) => setState(() => threshold = v)),
            Center(child: FilledButton(onPressed: busy ? null : _saveThreshold, child: const Text('保存分数门槛'))),
          ]))),
          if (tip != null) Padding(padding: const EdgeInsets.all(12), child: Text(tip!)),
        ]),
      ),
    );
  }
}
