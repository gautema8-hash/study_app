import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/ui.dart';

/// 登录页：手机号 + 验证码（开发模式验证码自动回显）
/// 视觉复用全应用统一设计系统 Ui；业务字段、校验、错误文案与跳转逐字保留。
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
  UiTone tone = UiTone.info;
  String? errorField;
  bool loadingSend = false;
  bool loadingLogin = false;

  Future<void> _send() async {
    setState(() { busy = true; tip = null; errorField = null; loadingSend = true; });
    try {
      final d = await Api.post('/api/auth/sms/send', body: {'phone': phone.text.trim()});
      smsCode.text = (d['devCode'] ?? '').toString(); // 开发模式自动回填
      setState(() { tip = '验证码已发送(开发模式自动回填)'; tone = UiTone.info; });
    } on ApiException catch (e) {
      setState(() { tip = e.message; tone = UiTone.error; errorField = 'phone'; });
    } finally {
      setState(() { busy = false; loadingSend = false; });
    }
  }

  Future<void> _login() async {
    setState(() { busy = true; tip = null; errorField = null; loadingLogin = true; });
    try {
      final d = await Api.post('/api/auth/sms/login', body: {'phone': phone.text.trim(), 'code': smsCode.text.trim()});
      Api.setSession(access: d['accessToken'], refresh: d['refreshToken']);
      Api.profileIds = (d['profileIds'] as List?)?.map((e) => (e as num).toInt()).toList() ?? const [];
      if (!mounted) return;
      Navigator.pushReplacementNamed(context, '/identity');
    } on ApiException catch (e) {
      setState(() { tip = e.message; tone = UiTone.error; errorField = 'code'; });
    } finally {
      setState(() { busy = false; loadingLogin = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Ui.bg,
      body: SafeArea(
        child: LayoutBuilder(builder: (context, constraints) {
          final wide = constraints.maxWidth > 560;
          final compact = constraints.maxHeight < 700;
          return SingleChildScrollView(
            physics: const ClampingScrollPhysics(),
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: constraints.maxHeight),
              child: Align(
                alignment: Alignment.topCenter,
                child: SizedBox(
                  width: wide ? 460 : double.infinity,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    UiBrandHeader(compact: compact),
                    const SizedBox(height: 20),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: UiCard(child: _form()),
                    ),
                    const SizedBox(height: 28),
                    Center(child: Container(width: 36, height: 3,
                        decoration: BoxDecoration(color: Ui.divider, borderRadius: BorderRadius.circular(2)))),
                    const SizedBox(height: 20),
                  ]),
                ),
              ),
            ),
          );
        }),
      ),
    );
  }

  Widget _form() => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          const Text('账号登录', style: Ui.title),
          const Spacer(),
          Container(width: 22, height: 2, decoration: BoxDecoration(color: Ui.gold, borderRadius: BorderRadius.circular(1))),
        ]),
        const SizedBox(height: 6),
        const Text('请输入手机号与验证码', style: Ui.caption),
        const SizedBox(height: 22),
        if (tip != null) ...[
          UiBanner(message: tip!, tone: tone),
          const SizedBox(height: 18),
        ],
        const UiLabel('手机号'),
        const SizedBox(height: 8),
        UiInput(controller: phone, hint: '请输入手机号', icon: Icons.smartphone_outlined,
            keyboardType: TextInputType.phone, textInputAction: TextInputAction.next,
            hasError: errorField == 'phone'),
        const SizedBox(height: 20),
        const UiLabel('验证码'),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: UiInput(controller: smsCode, hint: '请输入验证码', icon: Icons.pin_outlined,
              keyboardType: TextInputType.number, textInputAction: TextInputAction.done,
              hasError: errorField == 'code')),
          const SizedBox(width: 12),
          UiButton(label: '获取验证码', primary: false, onPressed: busy ? null : _send, loading: loadingSend),
        ]),
        const SizedBox(height: 28),
        UiButton(label: '登录', onPressed: busy ? null : _login, loading: loadingLogin,
            icon: Icons.arrow_forward_rounded, width: double.infinity),
      ]);
}
