import 'package:flutter/material.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 结果页：得分/达标/积分奖励/勋章（判分响应直达），统一视觉与左上角家长入口
class ResultPage extends StatelessWidget {
  final Map<String, dynamic> result;
  const ResultPage({super.key, required this.result});

  @override
  Widget build(BuildContext context) {
    final score = (result['score'] as num?)?.toInt() ?? 0;
    final invalidated = result['result'] == 3; // T-06 异常快答判废
    final passed = result['result'] == 1;
    final reward = (result['reward'] as Map?)?.cast<String, dynamic>() ?? {};
    final medals = (reward['newMedals'] as List?) ?? [];
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: Ui.bg,
        appBar: uiAppBar(title: '判分结果',
            leading: uiParentAccessButton(context, UiIdentity.student)),
        body: Center(child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: UiCard(child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(passed ? Icons.celebration_rounded : Icons.sentiment_dissatisfied_rounded,
                  size: 76, color: passed ? Ui.success : Ui.gold),
              const SizedBox(height: 14),
              if (!invalidated) Text('${score}分', style: const TextStyle(fontSize: 40, fontWeight: FontWeight.w700, color: Ui.ink)),
              const SizedBox(height: 6),
              Text(switch (result['result']) {
                1 => '达标！手机已解锁 🎉',
                3 => '本次作答无效：检测到异常快答，请认真作答',
                _ => '未达标，再挑战一次吧',
              }, style: Ui.section),
              const SizedBox(height: 20),
              if (reward.isNotEmpty) ...[
                Container(
                  width: double.infinity, padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(color: Ui.successBg, borderRadius: BorderRadius.circular(12)),
                  child: Column(children: [
                    Text('获得积分 +${reward['points']}', style: const TextStyle(color: Ui.success, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Text('连续打卡 ${reward['streak']} 天', style: const TextStyle(color: Ui.success)),
                    if (medals.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text('解锁勋章：${medals.join('、')}', style: const TextStyle(color: Ui.success)),
                    ],
                  ]),
                ),
                const SizedBox(height: 20),
              ],
              UiButton(label: '返回', onPressed: () => Navigator.pushReplacementNamed(context, '/gate'),
                  width: double.infinity, icon: Icons.arrow_forward_rounded),
            ])),
          ),
        )),
      ),
    );
  }
}
