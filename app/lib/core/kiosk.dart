import 'package:flutter/services.dart';

/// Android 原生守护通道（C2/C3）：LockTask 锁任务、设备管理员激活引导、拨号白名单。
/// iOS / 未激活设备管理员时原生实现返回 false，Flutter 侧自动降级为提醒级，不锁死。
class Kiosk {
  static const _channel = MethodChannel('xuexijiesuo/kiosk');

  static Future<bool> enable() async {
    try { return await _channel.invokeMethod<bool>('enable') ?? false; } catch (_) { return false; }
  }

  static Future<bool> disable() async {
    try { return await _channel.invokeMethod<bool>('disable') ?? false; } catch (_) { return false; }
  }

  static Future<bool> isActive() async {
    try { return await _channel.invokeMethod<bool>('isActive') ?? false; } catch (_) { return false; }
  }

  static Future<void> requestActivation() async {
    try { await _channel.invokeMethod('requestActivation'); } catch (_) {}
  }

  static Future<void> openDialer(String number) async {
    try { await _channel.invokeMethod('openDialer', {'number': number}); } catch (_) {}
  }
}
