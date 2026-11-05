import 'package:flutter/material.dart';

/// 全应用统一视觉系统：深靛主色 + 金铜点睛 + 中性浅底。
/// 所有页面共用同一套圆角、阴影、字体层级、状态色与组件，保证风格一致。
class Ui {
  static const Color bg = Color(0xFFF4F6FB);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color brandTop = Color(0xFF1B2554);
  static const Color brandBottom = Color(0xFF2C3B7E);
  static const Color primary = Color(0xFF2C3B7E);
  static const Color primarySoft = Color(0xFFEDF1FF);
  static const Color gold = Color(0xFFC9A96A);
  static const Color ink = Color(0xFF161C30);
  static const Color label = Color(0xFF4A5270);
  static const Color muted = Color(0xFF8A93A8);
  static const Color field = Color(0xFFF7F8FC);
  static const Color fieldBorder = Color(0xFFE5E9F3);
  static const Color divider = Color(0xFFE3E7F1);
  static const Color error = Color(0xFFB3413A);
  static const Color errorBg = Color(0xFFFCEFED);
  static const Color success = Color(0xFF2E7D5B);
  static const Color successBg = Color(0xFFEAF6F0);

  static const double rBrand = 30;
  static const double rCard = 24;
  static const double rControl = 14;
  static const double controlHeight = 56;

  static const LinearGradient brandGradient = LinearGradient(
    begin: Alignment.topLeft, end: Alignment.bottomRight,
    colors: [brandTop, brandBottom],
  );

  static final BoxShadow cardShadow = BoxShadow(
    color: const Color(0xFF233A6B).withValues(alpha: 0.09),
    blurRadius: 28, offset: const Offset(0, 12),
  );

  static const TextStyle display = TextStyle(fontSize: 31, fontWeight: FontWeight.w700, color: Colors.white, letterSpacing: 0.6, height: 1.2);
  static const TextStyle title = TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: ink, letterSpacing: 0.2);
  static const TextStyle section = TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: ink);
  static const TextStyle body = TextStyle(fontSize: 14, color: ink, height: 1.5);
  static const TextStyle caption = TextStyle(fontSize: 13, color: muted, height: 1.45);
  static const TextStyle labelStyle = TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: label, letterSpacing: 0.2);
}

/// 统一 AppBar：浅底 + 细分隔线；左上角始终有明确入口（由页面传入）
AppBar uiAppBar({
  required String title,
  Widget? leading,
  List<Widget>? actions,
  PreferredSizeWidget? bottom,
  bool centerTitle = false,
}) =>
    AppBar(
      backgroundColor: Ui.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      automaticallyImplyLeading: false,
      centerTitle: centerTitle,
      title: Text(title, style: Ui.section),
      leading: leading,
      actions: actions,
      bottom: bottom,
      shape: const Border(bottom: BorderSide(color: Ui.divider, width: 1)),
    );

/// 品牌区（登录/身份选择共用）
class UiBrandHeader extends StatelessWidget {
  final bool compact;
  final String subtitle;
  const UiBrandHeader({super.key, this.compact = false, this.subtitle = '先学习，后娱乐'});

  @override
  Widget build(BuildContext context) => Container(
        constraints: BoxConstraints(minHeight: compact ? 132 : 210),
        decoration: const BoxDecoration(
          gradient: Ui.brandGradient,
          borderRadius: BorderRadius.vertical(bottom: Radius.circular(Ui.rBrand)),
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
                  color: Ui.gold.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Ui.gold.withValues(alpha: 0.42)),
                ),
                child: Icon(Icons.lock_open_rounded, color: Ui.gold, size: compact ? 22 : 26),
              ),
              SizedBox(height: compact ? 10 : 22),
              Text('学习解锁', style: Ui.display.copyWith(fontSize: compact ? 26 : 31)),
              const SizedBox(height: 6),
              Text(subtitle, style: TextStyle(fontSize: 13, color: Colors.white.withValues(alpha: 0.72))),
              if (!compact) ...[
                const SizedBox(height: 18),
                Container(width: 34, height: 2, decoration: BoxDecoration(color: Ui.gold, borderRadius: BorderRadius.circular(1))),
              ],
            ],
          ),
        ),
      );
}

/// 统一悬浮卡片
class UiCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  const UiCard({super.key, required this.child, this.padding = const EdgeInsets.all(24)});

  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(
          color: Ui.surface,
          borderRadius: BorderRadius.circular(Ui.rCard),
          boxShadow: [Ui.cardShadow],
        ),
        child: child,
      );
}

/// 统一状态横幅（info / error / success）
enum UiTone { info, error, success }
class UiBanner extends StatelessWidget {
  final String message;
  final UiTone tone;
  const UiBanner({super.key, required this.message, required this.tone});

  @override
  Widget build(BuildContext context) {
    final (bg, fg, icon) = switch (tone) {
      UiTone.error => (Ui.errorBg, Ui.error, Icons.error_outline_rounded),
      UiTone.success => (Ui.successBg, Ui.success, Icons.check_circle_outline_rounded),
      UiTone.info => (Ui.primarySoft, Ui.primary, Icons.info_outline_rounded),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 18, color: fg),
        const SizedBox(width: 10),
        Expanded(child: Text(message, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: fg, height: 1.45))),
      ]),
    );
  }
}

/// 统一字段标签
class UiLabel extends StatelessWidget {
  final String text;
  const UiLabel(this.text, {super.key});
  @override
  Widget build(BuildContext context) => Text(text, style: Ui.labelStyle);
}

/// 统一输入框（常态/聚焦/错误三态）
class UiInput extends StatelessWidget {
  final TextEditingController controller;
  final String hint;
  final IconData icon;
  final TextInputType keyboardType;
  final TextInputAction textInputAction;
  final bool hasError;
  final bool obscure;
  final int? maxLength;
  const UiInput({
    super.key, required this.controller, required this.hint, required this.icon,
    required this.keyboardType, required this.textInputAction,
    this.hasError = false, this.obscure = false, this.maxLength,
  });

  @override
  Widget build(BuildContext context) {
    OutlineInputBorder border(Color c, double w) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(Ui.rControl),
          borderSide: BorderSide(color: c, width: w));
    return SizedBox(
      height: Ui.controlHeight,
      child: TextField(
        controller: controller, keyboardType: keyboardType, textInputAction: textInputAction,
        obscureText: obscure, maxLength: maxLength,
        cursorColor: hasError ? Ui.error : Ui.primary,
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500, color: Ui.ink),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(fontSize: 15, color: Ui.muted, fontWeight: FontWeight.w400),
          filled: true, fillColor: hasError ? Ui.errorBg : Ui.field,
          isDense: true, counterText: '',
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
          prefixIcon: Icon(icon, size: 20, color: hasError ? Ui.error : Ui.muted),
          prefixIconConstraints: const BoxConstraints(minWidth: 48, minHeight: 48),
          border: border(Colors.transparent, 0),
          enabledBorder: border(hasError ? Ui.error.withValues(alpha: 0.45) : Ui.fieldBorder, 1),
          focusedBorder: border(hasError ? Ui.error : Ui.primary, 1.6),
        ),
      ),
    );
  }
}

/// 统一主/次按钮（含加载态）
class UiButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final bool primary;
  final bool loading;
  final IconData? icon;
  final double? width;
  const UiButton({
    super.key, required this.label, required this.onPressed,
    this.primary = true, this.loading = false, this.icon, this.width,
  });

  @override
  Widget build(BuildContext context) {
    final child = loading
        ? SizedBox(width: 20, height: 20, child: CircularProgressIndicator(
            strokeWidth: 2, color: primary ? Colors.white : Ui.primary))
        : Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
            Text(label, style: TextStyle(fontSize: primary ? 16 : 14,
                fontWeight: FontWeight.w600, letterSpacing: primary ? 0.5 : 0)),
            if (icon != null) ...[const SizedBox(width: 8), Icon(icon, size: 18)],
          ]);
    return SizedBox(
      height: Ui.controlHeight, width: width,
      child: primary
          ? FilledButton(
              onPressed: loading ? null : onPressed,
              style: FilledButton.styleFrom(
                backgroundColor: Ui.primary,
                disabledBackgroundColor: Ui.primary.withValues(alpha: 0.45),
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
                elevation: 0,
              ),
              child: child)
          : OutlinedButton(
              onPressed: loading ? null : onPressed,
              style: OutlinedButton.styleFrom(
                backgroundColor: const Color(0xFFF1F4FD),
                foregroundColor: Ui.primary,
                side: const BorderSide(color: Color(0xFFDDE3F3)),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
                padding: const EdgeInsets.symmetric(horizontal: 16),
                elevation: 0,
              ),
              child: child),
    );
  }
}

/// 顶部单选式身份切换控件（SegmentedButton，单选语义）
enum UiIdentity { student, parent }
class UiIdentitySwitcher extends StatelessWidget {
  final UiIdentity current;
  final ValueChanged<UiIdentity> onRequestChange;
  const UiIdentitySwitcher({super.key, required this.current, required this.onRequestChange});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(color: Ui.surface, borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Ui.divider)),
        child: SegmentedButton<UiIdentity>(
          segments: const [
            ButtonSegment(value: UiIdentity.student, label: Text('学生端'), icon: Icon(Icons.school_outlined, size: 16)),
            ButtonSegment(value: UiIdentity.parent, label: Text('家长端'), icon: Icon(Icons.family_restroom_outlined, size: 16)),
          ],
          selected: {current},
          onSelectionChanged: (s) => onRequestChange(s.first),
          showSelectedIcon: false,
          style: ButtonStyle(
            backgroundColor: WidgetStateProperty.resolveWith((states) =>
                states.contains(WidgetState.selected) ? Ui.primary : Colors.transparent),
            foregroundColor: WidgetStateProperty.resolveWith((states) =>
                states.contains(WidgetState.selected) ? Colors.white : Ui.label),
            side: const WidgetStatePropertyAll(BorderSide(color: Colors.transparent)),
            shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
            textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            visualDensity: VisualDensity.compact,
          ),
        ),
      );
}
