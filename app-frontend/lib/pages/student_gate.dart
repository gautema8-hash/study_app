import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/child_mode.dart';
import '../core/kiosk.dart';

/// 孩子端直达页（C1/C2）：启动后由服务端 /api/quiz/gate 裁决三态：
/// quiz→自动出题 / using→使用中倒计时 / wait→带原因提示。
/// 网络不可达 → fail-open 临时开放（E-15，宁可漏管不可错锁）。
/// 不可返回退出；唯一退出路径是「长按标题 + 家长密码」（T-08/FR-15 管理通道）。
class StudentGate extends StatefulWidget {
  const StudentGate({super.key});
  @override
  State<StudentGate> createState() => _StudentGateState();
}

class _StudentGateState extends State<StudentGate> {
  String state = 'loading'; // loading | using | wait | failopen
  String message = '正在连接…';
  int remain = 0;
  bool kioskActive = false;
  bool busy = false;

  @override
  void initState() {
    super.initState();
    _decide();
  }

  Future<void> _decide() async {
    setState(() { state = 'loading'; message = '正在连接…'; busy = true; });
    try {
      final d = await Api.get('/api/quiz/gate');
      switch (d['state']) {
        case 'quiz':
          // 激活守护并自动开始答题（跳过任何首页/登录步骤）
          kioskActive = await Kiosk.enable();
          final task = await Api.post('/api/quiz/start');
          if (!mounted) return;
          Navigator.pushReplacementNamed(context, '/quiz', arguments: task)
              .then((_) => _decide()); // 答题结束后重新裁决（达标→使用中）
          return;
        case 'using':
          kioskActive = await Kiosk.enable();
          setState(() { state = 'using'; remain = (d['remainSeconds'] as num).toInt(); });
          break;
        case 'wait':
          // 例外：会员到期/临时开放 → 不启用强管控；其余 wait 留在应用内提示
          final exempt = d['reason'] == 'MEMBER_EXPIRED';
          if (exempt) await Kiosk.disable(); else kioskActive = await Kiosk.enable();
          setState(() { state = 'wait'; message = d['message']?.toString() ?? '暂不可答题'; });
          break;
        default:
          await Kiosk.disable();
          setState(() { state = 'wait'; message = '服务端返回异常，请稍后重试'; });
      }
    } on ApiException catch (e) {
      // 网络异常/后端不可达 → fail-open：临时开放，绝不锁死（E-15）
      await Kiosk.disable();
      setState(() { state = 'failopen'; message = '网络异常，已临时开放：${e.message}'; });
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _parentExit() async {
    final pwd = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('家长验证'),
        content: TextField(controller: pwd, keyboardType: TextInputType.number,
            obscureText: true, maxLength: 6, decoration: const InputDecoration(labelText: '6 位操作密码')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('验证')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await Api.post('/api/auth/password/parent/verify', body: {'password': pwd.text.trim()});
      await ChildMode.disable();
      await Kiosk.disable();
      if (mounted) Navigator.pushReplacementNamed(context, '/');
    } on ApiException catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  void _emergency() {
    showModalBottomSheet(context: context, builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const ListTile(title: Text('紧急呼叫（FR-17 永远可用）')),
        for (final n in ['110', '120', '119'])
          ListTile(leading: const Icon(Icons.phone), title: Text(n),
              onTap: () { Navigator.pop(ctx); Kiosk.openDialer(n); }),
      ]),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false, // 禁止返回键退出（配合原生 LockTask 禁止 HOME/切应用）
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          title: GestureDetector(
            onLongPress: _parentExit, // 家长唯一入口：长按 5 秒方向 + 密码
            child: const Text('学习解锁 · 孩子模式'),
          ),
          actions: [
            IconButton(icon: const Icon(Icons.phone), tooltip: '紧急呼叫', onPressed: _emergency),
            IconButton(icon: const Icon(Icons.emoji_events), tooltip: '激励中心',
                onPressed: () => Navigator.pushNamed(context, '/reward')),
          ],
        ),
        body: Center(child: Padding(padding: const EdgeInsets.all(24), child: _body())),
      ),
    );
  }

  Widget _body() {
    if (state == 'loading') return const CircularProgressIndicator();
    if (state == 'using') {
      return Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        const Icon(Icons.lock_open, size: 96, color: Colors.green),
        const SizedBox(height: 16),
        Text('使用中：剩余 ${(remain / 60).ceil()} 分钟', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        Text('守护状态：${kioskActive ? "已锁定在应用内" : "提醒级（未激活设备管理员）"}',
            style: const TextStyle(color: Colors.grey)),
        const SizedBox(height: 24),
        FilledButton(onPressed: busy ? null : _decide, child: const Text('刷新状态')),
      ]);
    }
    final failopen = state == 'failopen';
    return Column(mainAxisAlignment: MainAxisAlignment.center, children: [
      Icon(failopen ? Icons.lock_open : Icons.hourglass_top, size: 96,
          color: failopen ? Colors.green : Colors.orange),
      const SizedBox(height: 16),
      Text(message, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 12),
      if (failopen) const Text('管控已临时开放（E-15 安全底线），恢复联网后自动恢复', textAlign: TextAlign.center),
      const SizedBox(height: 24),
      FilledButton(onPressed: busy ? null : _decide, child: const Text('重试')),
      const SizedBox(height: 12),
      const Text('家长入口：长按顶部标题并输入操作密码', style: TextStyle(color: Colors.grey, fontSize: 12)),
    ]);
  }
}
