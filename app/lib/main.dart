import 'package:flutter/material.dart';
import 'pages/login_page.dart';
import 'pages/identity_page.dart';
import 'pages/parent_home.dart';
import 'pages/student_home.dart';
import 'pages/quiz_page.dart';
import 'pages/result_page.dart';
import 'pages/reward_page.dart';

/// 「学习解锁」客户端入口：登录 → 身份选择 → 家长端/学生端
void main() => runApp(const XuexijiesuoApp());

class XuexijiesuoApp extends StatelessWidget {
  const XuexijiesuoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '学习解锁',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      initialRoute: '/',
      routes: {
        '/': (_) => const LoginPage(),
        '/identity': (_) => const IdentityPage(),
        '/parent': (_) => const ParentHome(),
        '/student': (_) => const StudentHome(),
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
