import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 学生端（锁定态）：达标解锁 → 开始答题；激励中心入口
class StudentHome extends StatefulWidget {
  const StudentHome({super.key});
  @override
  State<StudentHome> createState() => _StudentHomeState();
}

class _StudentHomeState extends State<StudentHome> {
  Map<String, dynamic>? session;
  String? tip;
  bool busy = false;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final s = await Api.get('/api/control/session/current');
      setState(() => session = s);
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    }
  }

  Future<void> _startQuiz() async {
    setState(() { busy = true; tip = null; });
    try {
      final task = await Api.post('/api/quiz/start');
      if (!mounted) return;
      Navigator.pushNamed(context, '/quiz', arguments: task).then((_) => _load());
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    } finally {
      setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final locked = session == null || session!['locked'] == true;
    final remain = (session?['remainSeconds'] as num?) ?? 0;
    return Scaffold(
      backgroundColor: Ui.bg,
      appBar: uiAppBar(title: '学习解锁 · 学生',
        leading: uiParentAccessButton(context, UiIdentity.student),
        actions: [
        IconButton(icon: const Icon(Icons.emoji_events), tooltip: '激励中心',
            onPressed: () => Navigator.pushNamed(context, '/reward')),
      ]),
      body: Center(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        Icon(locked ? Icons.lock : Icons.lock_open, size: 96,
            color: locked ? Colors.redAccent : Colors.green),
        const SizedBox(height: 16),
        Text(locked ? '手机已锁定' : '使用中：剩余 ${(remain / 60).ceil()} 分钟',
            style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 32),
        if (locked)
          FilledButton(onPressed: busy ? null : _startQuiz, child: const Text('答题解锁手机'))
        else
          OutlinedButton(onPressed: _load, child: const Text('刷新状态')),
        if (tip != null) Padding(padding: const EdgeInsets.all(16), child: Text(tip!, textAlign: TextAlign.center)),
      ])),
    );
  }
}
