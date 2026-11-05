import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/kiosk.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 孩子端直达页：服务端 /api/quiz/gate 裁决三态（quiz/using/wait）。
/// - 左上角明确「家长入口」按钮（原长按标题保留为辅助入口，面板内含密码默认提示与设置入口）；
/// - 顶部单选式身份切换：学生→家长、家长→学生均需校验家长操作密码；
/// - 返回键禁用（PopScope），HOME 键由原生 onUserLeaveHint 拉回并在此上报 T-02 风控事件。
class StudentGate extends StatefulWidget {
  const StudentGate({super.key});
  @override
  State<StudentGate> createState() => _StudentGateState();
}

class _StudentGateState extends State<StudentGate> with WidgetsBindingObserver {
  String state = 'loading';
  String message = '正在连接…';
  int remain = 0;
  bool kioskActive = false;
  bool busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _decide();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    if (lifecycle == AppLifecycleState.paused || lifecycle == AppLifecycleState.hidden) {
      // HOME / 切离尝试：静默上报风控事件（T-02），家长端可见；原生已自动拉回
      Api.post('/api/risk/report', body: {'type': 'T-02', 'detail': '检测到 HOME/切离尝试'}).catchError((_) => <String, dynamic>{});
    } else if (lifecycle == AppLifecycleState.resumed) {
      _decide(); // 回到前台重新由服务端裁决，避免状态错乱
    }
  }

  Future<void> _decide() async {
    setState(() { state = 'loading'; message = '正在连接…'; busy = true; });
    try {
      final d = await Api.get('/api/quiz/gate');
      switch (d['state']) {
        case 'quiz':
          kioskActive = await Kiosk.enable();
          final task = await Api.post('/api/quiz/start');
          if (!mounted) return;
          Navigator.pushReplacementNamed(context, '/quiz', arguments: task).then((_) => _decide());
          return;
        case 'using':
          kioskActive = await Kiosk.enable();
          setState(() { state = 'using'; remain = (d['remainSeconds'] as num).toInt(); });
          break;
        case 'wait':
          final exempt = d['reason'] == 'MEMBER_EXPIRED';
          if (exempt) await Kiosk.disable(); else kioskActive = await Kiosk.enable();
          setState(() { state = 'wait'; message = d['message']?.toString() ?? '暂不可答题'; });
          break;
        default:
          await Kiosk.disable();
          setState(() { state = 'wait'; message = '服务端返回异常，请稍后重试'; });
      }
    } on ApiException catch (e) {
      await Kiosk.disable(); // fail-open：绝不锁死（E-15）
      setState(() { state = 'failopen'; message = '网络异常，已临时开放：${e.message}'; });
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  void _emergency() {
    showModalBottomSheet(context: context, builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Padding(padding: EdgeInsets.all(16),
            child: Text('紧急呼叫（FR-17 永远可用）', style: Ui.section)),
        for (final n in ['110', '120', '119'])
          ListTile(leading: const Icon(Icons.phone, color: Ui.primary), title: Text(n),
              onTap: () { Navigator.pop(ctx); Kiosk.openDialer(n); }),
      ]),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: Ui.bg,
        appBar: uiAppBar(
          title: '学习解锁 · 孩子模式',
          leading: uiParentAccessButton(context, UiIdentity.student),
          actions: [
            IconButton(icon: const Icon(Icons.phone_outlined), tooltip: '紧急呼叫', onPressed: _emergency),
            IconButton(icon: const Icon(Icons.emoji_events_outlined), tooltip: '激励中心',
                onPressed: () => Navigator.pushNamed(context, '/reward')),
          ],
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(64),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: UiIdentitySwitcher(current: UiIdentity.student, onRequestChange: (t) {
                if (t != UiIdentity.student) {
                  openParentAccess(context, current: UiIdentity.student, target: t);
                }
              }),
            ),
          ),
        ),
        body: Center(child: SingleChildScrollView(
          padding: const EdgeInsets.all(20), child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460), child: _body()),
        )),
      ),
    );
  }

  Widget _body() {
    if (state == 'loading') return const Padding(
        padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator(color: Ui.primary)));
    if (state == 'using') {
      return UiCard(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.lock_open_rounded, size: 72, color: Ui.success),
        const SizedBox(height: 14),
        Text('使用中：剩余 ${(remain / 60).ceil()} 分钟', style: Ui.title),
        const SizedBox(height: 8),
        Text(kioskActive
            ? '守护状态：已锁定在应用内'
            : '守护状态：提醒级 — 按 HOME 可切离，请家长在「家长入口 → 系统守护授权」完成一次授权',
            textAlign: TextAlign.center, style: Ui.caption),
        const SizedBox(height: 18),
        UiButton(label: '刷新状态', onPressed: busy ? null : _decide, width: 200),
      ]));
    }
    final failopen = state == 'failopen';
    return UiCard(child: Column(mainAxisSize: MainAxisSize.min, children: [
      Icon(failopen ? Icons.lock_open_rounded : Icons.hourglass_top_rounded, size: 72,
          color: failopen ? Ui.success : Ui.gold),
      const SizedBox(height: 14),
      Text(message, textAlign: TextAlign.center, style: Ui.section),
      const SizedBox(height: 8),
      if (failopen) const Text('管控已临时开放（E-15 安全底线），恢复联网后自动恢复',
          textAlign: TextAlign.center, style: Ui.caption),
      const SizedBox(height: 18),
      UiButton(label: '重试', onPressed: busy ? null : _decide, width: 200),
      const SizedBox(height: 12),
      const Text('家长入口：左上角按钮（含密码设置/修改与守护授权）', style: Ui.caption),
    ]));
  }
}
