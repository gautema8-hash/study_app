import 'package:flutter/material.dart';
import '../core/api.dart';

/// 登录页：手机号 + 验证码（开发模式验证码自动回显）
///
/// 视觉规范（仅本页，私有设计令牌，不引入依赖/不改项目结构）：
/// - 三层结构：深靛品牌区 / 悬浮白色表单卡片 / 中性底色；一层柔和阴影。
/// - 圆角体系 30(品牌区) / 24(卡片) / 14(输入与按钮)；水平节奏 20 页边距 + 24 卡片内边距。
/// - 字体层级 30 标题 / 20 卡片标题 / 16 输入 / 13 标签与提示 / 14 辅助按钮。
/// - 状态：info(靛蓝) 与 error(柔红) 统一横幅；错误同时描红对应字段；按钮加载态统一 spinner。
/// - 适配：SafeArea + LayoutBuilder + SingleChildScrollView + 460 最大宽 + 紧凑屏品牌区压缩。
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

  // 以下字段只参与视觉状态呈现，不改变任何业务流程
  _Tone tone = _Tone.info;
  String? errorField; // 'phone' | 'code' | null
  bool loadingSend = false;
  bool loadingLogin = false;

  Future<void> _send() async {
    setState(() { busy = true; tip = null; errorField = null; loadingSend = true; });
    try {
      final d = await Api.post('/api/auth/sms/send', body: {'phone': phone.text.trim()});
      smsCode.text = (d['devCode'] ?? '').toString(); // 开发模式自动回填
      setState(() { tip = '验证码已发送(开发模式自动回填)'; tone = _Tone.info; });
    } on ApiException catch (e) {
      setState(() { tip = e.message; tone = _Tone.error; errorField = 'phone'; });
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
      setState(() { tip = e.message; tone = _Tone.error; errorField = 'code'; });
    } finally {
      setState(() { busy = false; loadingLogin = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _D.bg,
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
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _BrandHeader(compact: compact),
                      const SizedBox(height: 20),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 20),
                        child: _LoginCard(
                          phone: phone,
                          smsCode: smsCode,
                          busy: busy,
                          loadingSend: loadingSend,
                          loadingLogin: loadingLogin,
                          tip: tip,
                          tone: tone,
                          errorField: errorField,
                          onSend: _send,
                          onLogin: _login,
                        ),
                      ),
                      const SizedBox(height: 28),
                      Center(
                        child: Container(
                          width: 36, height: 3,
                          decoration: BoxDecoration(
                            color: _D.divider,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                    ],
                  ),
                ),
              ),
            ),
          );
        }),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 设计令牌（私有，仅本页使用）
// ---------------------------------------------------------------------------
class _D {
  static const Color bg = Color(0xFFF4F6FB);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color brandTop = Color(0xFF1B2554);
  static const Color brandBottom = Color(0xFF2C3B7E);
  static const Color primary = Color(0xFF2C3B7E);
  static const Color gold = Color(0xFFC9A96A);
  static const Color ink = Color(0xFF161C30);
  static const Color label = Color(0xFF4A5270);
  static const Color muted = Color(0xFF8A93A8);
  static const Color field = Color(0xFFF7F8FC);
  static const Color fieldBorder = Color(0xFFE5E9F3);
  static const Color divider = Color(0xFFDDE2EF);
  static const Color error = Color(0xFFB3413A);
  static const Color errorBg = Color(0xFFFCEFED);
  static const Color infoBg = Color(0xFFEDF1FF);

  static const double rCard = 24;
  static const double rControl = 14;
  static const double controlHeight = 56;

  static final BoxShadow cardShadow = BoxShadow(
    color: const Color(0xFF233A6B).withValues(alpha: 0.09),
    blurRadius: 28,
    offset: const Offset(0, 12),
  );
}

enum _Tone { info, error }

// ---------------------------------------------------------------------------
// 品牌区：深靛渐变 + 克制的金色点缀（无图片、无动效、无装饰堆砌）
// ---------------------------------------------------------------------------
class _BrandHeader extends StatelessWidget {
  final bool compact;
  const _BrandHeader({required this.compact});

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(minHeight: compact ? 132 : 210),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [_D.brandTop, _D.brandBottom],
        ),
        borderRadius: BorderRadius.vertical(bottom: Radius.circular(30)),
      ),
      child: Padding(
        padding: EdgeInsets.fromLTRB(28, compact ? 16 : 24, 28, compact ? 16 : 28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: compact ? 42 : 50, height: compact ? 42 : 50,
              decoration: BoxDecoration(
                color: _D.gold.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: _D.gold.withValues(alpha: 0.42), width: 1),
              ),
              child: Icon(Icons.lock_open_rounded, color: _D.gold, size: compact ? 22 : 26),
            ),
            SizedBox(height: compact ? 10 : 22),
            Text('学习解锁',
                style: TextStyle(fontSize: compact ? 26 : 31, fontWeight: FontWeight.w700,
                    color: Colors.white, letterSpacing: 0.6, height: 1.2)),
            const SizedBox(height: 6),
            Text('先学习，后娱乐',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w400,
                    color: Colors.white.withValues(alpha: 0.72), letterSpacing: 0.4)),
            if (!compact) ...[
              const SizedBox(height: 18),
              Container(width: 34, height: 2,
                  decoration: BoxDecoration(color: _D.gold, borderRadius: BorderRadius.circular(1))),
            ],
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 表单卡片：标题层级、统一状态横幅、字段标签与输入、主次按钮
// ---------------------------------------------------------------------------
class _LoginCard extends StatelessWidget {
  final TextEditingController phone;
  final TextEditingController smsCode;
  final bool busy;
  final bool loadingSend;
  final bool loadingLogin;
  final String? tip;
  final _Tone tone;
  final String? errorField;
  final VoidCallback onSend;
  final VoidCallback onLogin;

  const _LoginCard({
    required this.phone, required this.smsCode, required this.busy,
    required this.loadingSend, required this.loadingLogin, required this.tip,
    required this.tone, required this.errorField,
    required this.onSend, required this.onLogin,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: _D.surface,
        borderRadius: BorderRadius.circular(_D.rCard),
        boxShadow: [_D.cardShadow],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Text('账号登录',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700,
                      color: _D.ink, letterSpacing: 0.2)),
              const Spacer(),
              Container(width: 22, height: 2,
                  decoration: BoxDecoration(color: _D.gold, borderRadius: BorderRadius.circular(1))),
            ],
          ),
          const SizedBox(height: 6),
          const Text('请输入手机号与验证码',
              style: TextStyle(fontSize: 13, color: _D.muted, height: 1.4)),
          const SizedBox(height: 22),
          if (tip != null) ...[
            _StatusBanner(message: tip!, tone: tone),
            const SizedBox(height: 18),
          ],
          const _FieldLabel('手机号'),
          const SizedBox(height: 8),
          _InputField(
            controller: phone,
            hint: '请输入手机号',
            icon: Icons.smartphone_outlined,
            keyboardType: TextInputType.phone,
            textInputAction: TextInputAction.next,
            hasError: errorField == 'phone',
          ),
          const SizedBox(height: 20),
          const _FieldLabel('验证码'),
          const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(
                child: _InputField(
                  controller: smsCode,
                  hint: '请输入验证码',
                  icon: Icons.pin_outlined,
                  keyboardType: TextInputType.number,
                  textInputAction: TextInputAction.done,
                  hasError: errorField == 'code',
                  numeric: true,
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                height: _D.controlHeight,
                child: OutlinedButton(
                  onPressed: busy ? null : onSend,
                  style: OutlinedButton.styleFrom(
                    backgroundColor: const Color(0xFFF1F4FD),
                    disabledBackgroundColor: const Color(0xFFF1F4FD).withValues(alpha: 0.5),
                    foregroundColor: _D.primary,
                    side: const BorderSide(color: Color(0xFFDDE3F3), width: 1),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(_D.rControl)),
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    elevation: 0,
                    textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                  ),
                  child: loadingSend
                      ? const SizedBox(width: 18, height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2, color: _D.primary))
                      : const Text('获取验证码'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 28),
          SizedBox(
            height: _D.controlHeight,
            width: double.infinity,
            child: FilledButton(
              onPressed: busy ? null : onLogin,
              style: FilledButton.styleFrom(
                backgroundColor: _D.primary,
                disabledBackgroundColor: _D.primary.withValues(alpha: 0.45),
                foregroundColor: Colors.white,
                disabledForegroundColor: Colors.white.withValues(alpha: 0.85),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(_D.rControl)),
                elevation: 0,
                textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: 0.5),
              ),
              child: loadingLogin
                  ? const SizedBox(width: 20, height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('登录'),
                        SizedBox(width: 8),
                        Icon(Icons.arrow_forward_rounded, size: 18),
                      ],
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 统一状态横幅：info / error 同一套圆角、图标、字号、行高与内边距
// ---------------------------------------------------------------------------
class _StatusBanner extends StatelessWidget {
  final String message;
  final _Tone tone;
  const _StatusBanner({required this.message, required this.tone});

  @override
  Widget build(BuildContext context) {
    final isError = tone == _Tone.error;
    final fg = isError ? _D.error : _D.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: isError ? _D.errorBg : _D.infoBg,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(isError ? Icons.error_outline_rounded : Icons.check_circle_outline_rounded,
              size: 18, color: fg),
          const SizedBox(width: 10),
          Expanded(
            child: Text(message,
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: fg, height: 1.45)),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 字段标签（独立层级，不依赖浮动 label 的抖动）
// ---------------------------------------------------------------------------
class _FieldLabel extends StatelessWidget {
  final String text;
  const _FieldLabel(this.text);
  @override
  Widget build(BuildContext context) => Text(text,
      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600,
          color: _D.label, letterSpacing: 0.2));
}

// ---------------------------------------------------------------------------
// 统一输入框：常态/聚焦/错误三态，前缀图标、填充、圆角与内边距一致
// ---------------------------------------------------------------------------
class _InputField extends StatelessWidget {
  final TextEditingController controller;
  final String hint;
  final IconData icon;
  final TextInputType keyboardType;
  final TextInputAction textInputAction;
  final bool hasError;
  final bool numeric;
  const _InputField({
    required this.controller, required this.hint, required this.icon,
    required this.keyboardType, required this.textInputAction,
    required this.hasError, this.numeric = false,
  });

  @override
  Widget build(BuildContext context) {
    final borderColor = hasError ? _D.error.withValues(alpha: 0.45) : _D.fieldBorder;
    final fill = hasError ? _D.errorBg : _D.field;
    OutlineInputBorder outline(Color color, double width) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(_D.rControl),
          borderSide: BorderSide(color: color, width: width),
        );
    return SizedBox(
      height: _D.controlHeight,
      child: TextField(
        controller: controller,
        keyboardType: keyboardType,
        textInputAction: textInputAction,
        cursorColor: hasError ? _D.error : _D.primary,
        style: TextStyle(
          fontSize: 16, fontWeight: FontWeight.w500, color: _D.ink,
          fontFeatures: numeric ? const [FontFeature.tabularFigures()] : null,
        ),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(fontSize: 15, color: _D.muted, fontWeight: FontWeight.w400),
          filled: true,
          fillColor: fill,
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
          prefixIcon: Icon(icon, size: 20, color: hasError ? _D.error : _D.muted),
          prefixIconConstraints: const BoxConstraints(minWidth: 48, minHeight: 48),
          border: outline(Colors.transparent, 0),
          enabledBorder: outline(borderColor, 1),
          focusedBorder: outline(hasError ? _D.error : _D.primary, 1.6),
        ),
      ),
    );
  }
}
