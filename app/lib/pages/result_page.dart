import 'package:flutter/material.dart';

/// 结果页：得分/达标/积分奖励/勋章（判分响应直达）
class ResultPage extends StatelessWidget {
  final Map<String, dynamic> result;
  const ResultPage({super.key, required this.result});

  @override
  Widget build(BuildContext context) {
    final score = (result['score'] as num?)?.toInt() ?? 0;
    final passed = result['result'] == 1;
    final reward = (result['reward'] as Map?)?.cast<String, dynamic>() ?? {};
    final medals = (reward['newMedals'] as List?) ?? [];
    return Scaffold(
      appBar: AppBar(title: const Text('判分结果'), automaticallyImplyLeading: false),
      body: Center(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        Icon(passed ? Icons.celebration : Icons.sentiment_dissatisfied, size: 96,
            color: passed ? Colors.green : Colors.orange),
        const SizedBox(height: 16),
        Text('${score}分', style: Theme.of(context).textTheme.displayMedium),
        Text(passed ? '达标！手机已解锁 🎉' : '未达标，再挑战一次吧', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 24),
        if (reward.isNotEmpty)
          Card(margin: const EdgeInsets.symmetric(horizontal: 32), child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(children: [
              Text('获得积分 +${reward['points']}'),
              Text('连续打卡 ${reward['streak']} 天'),
              if (medals.isNotEmpty) Text('解锁勋章：${medals.join('、')}'),
            ]),
          )),
        const SizedBox(height: 32),
        FilledButton(onPressed: () => Navigator.pushReplacementNamed(context, '/student'),
            child: const Text('返回')),
      ])),
    );
  }
}
