import 'package:flutter/material.dart';
import '../core/api.dart';

/// 登录页：手机号 + 验证码（开发模式验证码自动回显）
class LoginPage extends StatefulWidget {
  const LoginPage({super.key});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final phone = TextEditingController(text: '13800001111');
  final smsCode = TextEditingController();
  String? tip;
  bool busy = false;

  Future<void> _send() async {
    setState(() { busy = true; tip = null; });
    try {
      final d = await Api.post('/api/auth/sms/send', body: {'phone': phone.text.trim()});
      smsCode.text = (d['devCode'] ?? '').toString(); // 开发模式自动回填
      setState(() => tip = '验证码已发送(开发模式自动回填)');
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    } finally {
      setState(() => busy = false);
    }
  }

  Future<void> _login() async {
    setState(() { busy = true; tip = null; });
    try {
      final d = await Api.post('/api/auth/sms/login', body: {'phone': phone.text.trim(), 'code': smsCode.text.trim()});
      Api.setSession(access: d['accessToken'], refresh: d['refreshToken']);
      Api.profileIds = (d['profileIds'] as List?)?.map((e) => (e as num).toInt()).toList() ?? const [];
      if (!mounted) return;
      Navigator.pushReplacementNamed(context, '/identity');
    } on ApiException catch (e) {
      setState(() => tip = e.message);
    } finally {
      setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('学习解锁 · 登录')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(children: [
          TextField(controller: phone, keyboardType: TextInputType.phone,
              decoration: const InputDecoration(labelText: '手机号')),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: TextField(controller: smsCode, keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: '验证码'))),
            const SizedBox(width: 12),
            OutlinedButton(onPressed: busy ? null : _send, child: const Text('获取验证码')),
          ]),
          const SizedBox(height: 24),
          FilledButton(onPressed: busy ? null : _login, child: const Text('登录')),
          if (tip != null) Padding(padding: const EdgeInsets.only(top: 16), child: Text(tip!)),
        ]),
      ),
    );
  }
}
