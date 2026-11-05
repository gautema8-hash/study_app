import 'package:flutter/material.dart';
import 'core/api.dart';
import 'core/child_mode.dart';
import 'pages/login_page.dart';
import 'pages/identity_page.dart';
import 'pages/parent_home.dart';
import 'pages/student_home.dart';
import 'pages/student_gate.dart';
import 'pages/quiz_page.dart';
import 'pages/result_page.dart';
import 'pages/reward_page.dart';

/// 「学习解锁」客户端入口。
/// 孩子设备（本地 childMode 标记）→ 开机/启动直达 /gate（服务端裁决三态）；
/// 家长设备或未标记 → 保持原有登录流程，行为零改动。
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  bool child = await ChildMode.enabled();
  if (child) {
    final saved = await ChildMode.token();
    if (saved == null || saved.isEmpty) {
      await ChildMode.disable();
      child = false;
    } else {
      Api.setSession(access: saved);
    }
  }
  runApp(XuexijiesuoApp(childMode: child));
}

class XuexijiesuoApp extends StatelessWidget {
  final bool childMode;
  const XuexijiesuoApp({super.key, required this.childMode});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '学习解锁',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      initialRoute: childMode ? '/gate' : '/',
      routes: {
        '/': (_) => const LoginPage(),
        '/identity': (_) => const IdentityPage(),
        '/parent': (_) => const ParentHome(),
        '/student': (_) => const StudentHome(),
        '/gate': (_) => const StudentGate(),
        '/reward': (_) => const RewardPage(),
      },
      onGenerateRoute: (settings) {
        if (settings.name == '/quiz') {
          final args = settings.arguments as Map<String, dynamic>;
          return MaterialPageRoute(builder: (_) => QuizPage(task: args));
        }
        if (settings.name == '/result') {
          final args = settings.arguments as Map<String, dynamic>;
          return MaterialPageRoute(builder: (_) => ResultPage(result: args));
        }
        return null;
      },
    );
  }
}
