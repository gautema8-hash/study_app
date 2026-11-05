import 'dart:convert';
import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/kiosk.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 答题页（FR-12）：切出提醒文案占位；单题作答计时简化为整卷计时
class QuizPage extends StatefulWidget {
  final Map<String, dynamic> task;
  const QuizPage({super.key, required this.task});
  @override
  State<QuizPage> createState() => _QuizPageState();
}

class _QuizPageState extends State<QuizPage> {
  late final List<dynamic> questions;
  late final int taskId;
  final answers = <int, String>{};
  int index = 0;
  bool busy = false;
  String? tip;

  @override
  void initState() {
    super.initState();
    taskId = (widget.task['taskId'] as num).toInt();
    questions = widget.task['questions'] as List<dynamic>;
  }

  List<String> _options(Map<String, dynamic> q) {
    try {
      final raw = q['options'];
      if (raw is String) return (jsonDecode(raw) as List).map((e) => e.toString()).toList();
      if (raw is List) return raw.map((e) => e.toString()).toList();
    } catch (_) {}
    return const [];
  }

  Future<void> _submit() async {
    setState(() { busy = true; tip = null; });
    try {
      final body = {
        'taskId': taskId,
        'answers': questions.map((q) {
          final qid = (q['id'] as num).toInt();
          return {'questionId': qid, 'answer': answers[qid] ?? '', 'costMs': 0, 'switchCount': 0};
        }).toList(),
      };
      final r = await Api.post('/api/quiz/submit', body: body);
      if (!mounted) return;
      Navigator.pushReplacementNamed(context, '/result', arguments: r);
    } on ApiException catch (e) {
      setState(() { tip = e.message; busy = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = questions[index] as Map<String, dynamic>;
    final qid = (q['id'] as num).toInt();
    final options = _options(q);
    return PopScope(
      canPop: false, // 孩子端：答题中禁止返回退出（C2）
      child: Scaffold(
      backgroundColor: Ui.bg,
      appBar: uiAppBar(title: '答题 ${index + 1}/${questions.length}',
        leading: uiParentAccessButton(context, UiIdentity.student),
        actions: [
          IconButton(icon: const Icon(Icons.phone_outlined, color: Ui.primary), tooltip: '紧急呼叫',
              onPressed: () => Kiosk.openDialer('110')),
        ]),
      body: Padding(padding: const EdgeInsets.all(20), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Container(
          width: double.infinity, padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(color: Ui.surface,
              borderRadius: BorderRadius.circular(Ui.rCard), boxShadow: [Ui.cardShadow]),
          child: Text(q['stem'].toString(), style: Ui.title),
        ),
        const SizedBox(height: 24),
        ...options.map((o) => RadioListTile<String>(
              value: o.split('.').first.trim(), groupValue: answers[qid],
              title: Text(o),
              activeColor: Ui.primary,
              onChanged: (v) => setState(() => answers[qid] = v ?? ''),
            )),
        const Spacer(),
        if (tip != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: UiBanner(message: tip!, tone: UiTone.error)),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          if (index > 0)
            UiButton(label: '上一题', primary: false, onPressed: () => setState(() => index--))
          else const SizedBox(),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Ui.primary,
                disabledBackgroundColor: Ui.primary.withValues(alpha: 0.45),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)), elevation: 0),
            onPressed: busy ? null : () {
              if (answers[qid] == null) {
                setState(() => tip = '请先选择答案');
              } else if (index < questions.length - 1) {
                setState(() { index++; tip = null; });
              } else {
                _submit();
              }
            },
            child: Text(index < questions.length - 1 ? '下一题' : '提交判分'),
          ),
        ]),
      ])),
      ),
    );
  }
}
