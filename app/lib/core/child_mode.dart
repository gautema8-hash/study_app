import 'package:shared_preferences/shared_preferences.dart';

/// 孩子模式本地标记（C3）：仅孩子设备写入；家长设备不写 → 开机自启/守护完全不生效。
/// Android 原生 BootReceiver 读取同一份 FlutterSharedPreferences 的 "flutter.childMode"。
class ChildMode {
  static const String _kMode = 'childMode';
  static const String _kToken = 'childToken';

  static Future<bool> enabled() async {
    final p = await SharedPreferences.getInstance();
    return p.getBool(_kMode) ?? false;
  }

  static Future<String?> token() async {
    final p = await SharedPreferences.getInstance();
    return p.getString(_kToken);
  }

  static Future<void> enable(String studentToken) async {
    final p = await SharedPreferences.getInstance();
    await p.setBool(_kMode, true);
    await p.setString(_kToken, studentToken);
  }

  static Future<void> refreshToken(String studentToken) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_kToken, studentToken);
  }

  /// 仅家长密码验证通过后调用（孩子无法自助退出）
  static Future<void> disable() async {
    final p = await SharedPreferences.getInstance();
    await p.remove(_kMode);
    await p.remove(_kToken);
  }
}
