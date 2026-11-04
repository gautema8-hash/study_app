import 'package:flutter/material.dart';
import '../core/api.dart';

/// 激励中心（FR-22）：积分/勋章/打卡日历（仅本人数据，无排行无抽奖）
class RewardPage extends StatefulWidget {
  const RewardPage({super.key});
  @override
  State<RewardPage> createState() => _RewardPageState();
}

class _RewardPageState extends State<RewardPage> {
  Map<String, dynamic>? d;
  String? tip;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final res = await Api.get('/api/reward/me');
      setState(() => d = res);
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('激励中心')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: d == null
          ? ListView(children: [Padding(padding: const EdgeInsets.all(24), child: Text(tip ?? '加载中...'))])
          : ListView(padding: const EdgeInsets.all(16), children: [
              Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
                _stat('积分', '${d!['balance']}'),
                _stat('连续打卡', '${d!['streak']} 天'),
                _stat('勋章', '${(d!['medals'] as List).length}'),
              ]),
              const SizedBox(height: 16),
              Text('勋章墙', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Wrap(spacing: 8, children: [
                for (final m in d!['medals'] as List)
                  Chip(label: Text(_medalName(m['code'].toString()))),
                if ((d!['medals'] as List).isEmpty) const Text('暂无勋章，答满分解锁第一个吧！'),
              ]),
              const SizedBox(height: 16),
              Text('打卡日历（近60天）', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 6, children: [
                for (final day in d!['checkins'] as List)
                  Chip(label: Text(day.toString().substring(5)), visualDensity: VisualDensity.compact),
              ]),
            ]),
      ),
    );
  }

  Widget _stat(String label, String value) => Column(children: [
        Text(value, style: Theme.of(context).textTheme.headlineMedium),
        Text(label),
      ]);

  String _medalName(String code) => switch (code) {
        'perfect' => '单次满分', 'streak7' => '连续打卡7天', _ => code,
      };
}
