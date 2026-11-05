import 'dart:convert';
import 'package:http/http.dart' as http;

/// API 基址：真机调试用 `--dart-define=API_BASE=http://<电脑局域网IP>:8080`
/// 模拟器默认 10.0.2.2 映射宿主机
const String kApiBase = String.fromEnvironment('API_BASE', defaultValue: 'http://10.0.2.2:8080');

/// 轻量 API 客户端：统一 Bearer 注入与 {code,msg,data} 解包
class Api {
  static String? _token;
  static String? _refreshToken;
  static List<int> profileIds = const [];

  static void setSession({required String access, String? refresh}) {
    _token = access;
    if (refresh != null) _refreshToken = refresh;
  }

  static void setScopedToken(String access) => _token = access;

  static Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (_token != null) 'Authorization': 'Bearer $_token',
      };

  static Future<Map<String, dynamic>> get(String path) async => _run(http.get(Uri.parse('$kApiBase$path'), headers: _headers));

  static Future<Map<String, dynamic>> post(String path, {Object? body}) async =>
      _run(http.post(Uri.parse('$kApiBase$path'), headers: _headers, body: jsonEncode(body ?? {})));

  static Future<Map<String, dynamic>> put(String path, {Object? body}) async =>
      _run(http.put(Uri.parse('$kApiBase$path'), headers: _headers, body: jsonEncode(body ?? {})));

  static Future<Map<String, dynamic>> delete(String path) async =>
      _run(http.delete(Uri.parse('$kApiBase$path'), headers: _headers));

  static Future<Map<String, dynamic>> _run(Future<http.Response> f) async {
    http.Response r;
    try {
      r = await f;
    } catch (e) {
      // 网络层异常（连接拒绝/明文被禁/DNS等）透出真实原因
      throw ApiException('网络异常: $e');
    }
    final Map<String, dynamic> json;
    try {
      json = jsonDecode(utf8.decode(r.bodyBytes)) as Map<String, dynamic>;
    } catch (e) {
      throw ApiException('响应非JSON(HTTP ${r.statusCode}): ${utf8.decode(r.bodyBytes).substring(0, r.bodyBytes.length > 120 ? 120 : r.bodyBytes.length)}');
    }
    if (json['code'] != 0) {
      throw ApiException(json['msg']?.toString() ?? '请求失败(HTTP ${r.statusCode})');
    }
    return (json['data'] as Map?)?.cast<String, dynamic>() ?? {};
  }
}

class ApiException implements Exception {
  final String message;
  ApiException(this.message);
  @override
  String toString() => message;
}
