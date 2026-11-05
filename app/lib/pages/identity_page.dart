import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/child_mode.dart';
import '../core/kiosk.dart';

/// 身份选择页（FR-32）：家长 / 孩子；孩子需已绑定学生档案
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
      // 孩子设备：写入本地标记（开机自启/直达/守护仅对本设备生效），并引导一次性设备管理员授权
      await ChildMode.enable(d['accessToken']);
      await Kiosk.requestActivation();
      if (context.mounted) Navigator.pushReplacementNamed(context, '/gate');
    } on ApiException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('选择身份')),
      body: Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          SizedBox(
            width: 220, height: 90,
            child: FilledButton(
              onPressed: () => _pick(context, 'parent'),
              child: const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(Icons.family_restroom), SizedBox(width: 8), Text('我是家长（管理）'),
              ])),
          ),
          const SizedBox(height: 24),
          SizedBox(
            width: 220, height: 90,
            child: FilledButton.tonal(
              onPressed: () => _pick(context, 'student'),
              child: const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(Icons.school), SizedBox(width: 8), Text('我是孩子（被管控）'),
              ])),
          ),
        ]),
      ),
    );
  }
}
