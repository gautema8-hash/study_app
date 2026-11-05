import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/child_mode.dart';
import '../core/kiosk.dart';
import '../core/ui.dart';
import '../core/identity.dart';

/// 首次身份选择（登录后的入口，尚未进入任何端，不要求密码）。
/// 家长操作密码的设置/修改入口在此即可打开，避免后续要求输入密码却没有设置入口。
class IdentityPage extends StatelessWidget {
  const IdentityPage({super.key});

  Future<void> _pick(BuildContext context, String scope) async {
    try {
      if (scope == 'parent') {
        final d = await Api.post('/api/auth/identity/select', body: {'scope': 'parent'});
        Api.setScopedToken(d['accessToken']);
        if (context.mounted) Navigator.pushReplacementNamed(context, '/parent');
        return;
      }
      if (Api.profileIds.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('暂无学生档案：请先在家长端生成绑定码并绑定设备')));
        return;
      }
      final d = await Api.post('/api/auth/identity/select', body: {'scope': 'student', 'profileId': Api.profileIds.first});
      Api.setScopedToken(d['accessToken']);
      await ChildMode.enable(d['accessToken']);
      await Kiosk.requestActivation();
      if (context.mounted) Navigator.pushReplacementNamed(context, '/gate');
    } on ApiException catch (e) {
      if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Ui.bg,
      body: SafeArea(
        child: LayoutBuilder(builder: (context, c) => SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: c.maxHeight),
            child: Align(
              alignment: Alignment.topCenter,
              child: SizedBox(
                width: c.maxWidth > 560 ? 460 : double.infinity,
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  UiBrandHeader(compact: c.maxHeight < 700, subtitle: '选择身份 · 开启专属体验'),
                  const SizedBox(height: 20),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      Row(children: [
                        const Text('选择身份', style: Ui.title),
                        const Spacer(),
                        Container(width: 22, height: 2,
                            decoration: BoxDecoration(color: Ui.gold, borderRadius: BorderRadius.circular(1))),
                      ]),
                      const SizedBox(height: 6),
                      const Text('登录后首次选择无需密码；之后切换身份需验证家长操作密码', style: Ui.caption),
                      const SizedBox(height: 22),
                      _identityTile(context, scope: 'parent', icon: Icons.family_restroom_outlined,
                          title: '我是家长', desc: '题库规则 · 会员 · 学习报告', primary: true),
                      const SizedBox(height: 12),
                      _identityTile(context, scope: 'student', icon: Icons.school_outlined,
                          title: '我是孩子', desc: '答题解锁 · 积分 · 勋章', primary: false),
                      const SizedBox(height: 20),
                      const Divider(color: Ui.divider, height: 1),
                      const SizedBox(height: 14),
                      OutlinedButton.icon(
                        icon: const Icon(Icons.password_rounded, size: 18),
                        label: const Text('设置 / 修改家长操作密码'),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Ui.primary,
                          side: const BorderSide(color: Ui.fieldBorder),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
                          minimumSize: const Size.fromHeight(48),
                        ),
                        onPressed: () => openParentAccess(context, current: UiIdentity.parent),
                      ),
                    ])),
                  ),
                  const SizedBox(height: 24),
                ]),
              ),
            ),
          ),
        )),
      ),
    );
  }

  Widget _identityTile(BuildContext context,
      {required String scope, required IconData icon, required String title,
       required String desc, required bool primary}) {
    final fg = primary ? Colors.white : Ui.primary;
    return Material(
      color: primary ? Ui.primary : Ui.primarySoft,
      borderRadius: BorderRadius.circular(Ui.rControl),
      child: InkWell(
        borderRadius: BorderRadius.circular(Ui.rControl),
        onTap: () => _pick(context, scope),
        child: Container(
          height: 84, padding: const EdgeInsets.symmetric(horizontal: 18),
          child: Row(children: [
            Icon(icon, color: fg, size: 28),
            const SizedBox(width: 16),
            Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: fg)),
              const SizedBox(height: 4),
              Text(desc, style: TextStyle(fontSize: 12, color: primary ? Colors.white70 : Ui.label)),
            ])),
            Icon(Icons.arrow_forward_ios_rounded, size: 16, color: fg),
          ]),
        ),
      ),
    );
  }
}
