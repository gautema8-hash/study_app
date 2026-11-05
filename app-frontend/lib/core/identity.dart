import 'package:flutter/material.dart';
import 'api.dart';
import 'child_mode.dart';
import 'kiosk.dart';
import 'ui.dart';

/// 家长入口面板：统一承载「密码校验 / 设置或修改操作密码 / 身份切换 / 退出登录 / 系统守护授权」。
/// - 未设置密码时给出明确的默认提示与首次设置入口，杜绝「要求输入密码却没有设置入口」；
/// - 校验失败保持当前身份并给出提示，不切换、不退出；
/// - 学生端与家长端切换均强制校验家长设置的 6 位操作密码。
Future<void> openParentAccess(
  BuildContext context, {
  required UiIdentity current,
  UiIdentity? target,
}) async {
  await showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => _ParentAccessSheet(current: current, target: target),
  );
}

class _ParentAccessSheet extends StatefulWidget {
  final UiIdentity current;
  final UiIdentity? target;
  const _ParentAccessSheet({required this.current, this.target});

  @override
  State<_ParentAccessSheet> createState() => _ParentAccessSheetState();
}

class _ParentAccessSheetState extends State<_ParentAccessSheet> {
  final pwd = TextEditingController();
  final newPwd = TextEditingController();
  final oldPwd = TextEditingController();
  bool loading = false;
  bool verified = false;
  bool showPwdSettings = false;
  bool? hasPassword;
  String? error;
  String? notice;

  @override
  void initState() {
    super.initState();
    _loadStatus();
  }

  Future<void> _loadStatus() async {
    try {
      final d = await Api.get('/api/auth/password/parent/status');
      setState(() => hasPassword = d['hasPassword'] == true);
    } on ApiException catch (e) {
      setState(() => error = e.message);
    }
  }

  Future<void> _verify() async {
    setState(() { loading = true; error = null; notice = null; });
    try {
      await Api.post('/api/auth/password/parent/verify', body: {'password': pwd.text.trim()});
      setState(() { verified = true; notice = '验证通过，请选择要执行的操作'; });
    } on ApiException catch (e) {
      // 校验失败：保持当前身份，仅展示提示
      setState(() { error = e.message; verified = false; });
    } finally {
      setState(() => loading = false);
    }
  }

  Future<void> _savePassword() async {
    final np = newPwd.text.trim();
    if (!RegExp(r'^\d{6}$').hasMatch(np)) {
      setState(() => error = '操作密码须为6位数字');
      return;
    }
    final wasSet = hasPassword == true;
    setState(() { loading = true; error = null; });
    try {
      await Api.post('/api/auth/password/parent', body: {
        'password': np,
        if (hasPassword == true) 'oldPassword': oldPwd.text.trim(),
      });
      setState(() {
        hasPassword = true; showPwdSettings = false;
        notice = wasSet ? '操作密码已更新' : '操作密码已设置，可用于身份切换';
        newPwd.clear(); oldPwd.clear();
      });
      await _loadStatus();
    } on ApiException catch (e) {
      setState(() => error = e.message);
    } finally {
      setState(() => loading = false);
    }
  }

  Future<void> _switchTo(UiIdentity to) async {
    try {
      if (to == UiIdentity.student && Api.profileIds.isEmpty) {
        setState(() => error = '暂无学生档案，请先由家长绑定孩子设备');
        return;
      }
      final d = await Api.post('/api/auth/identity/select', body: to == UiIdentity.parent
          ? {'scope': 'parent'}
          : {'scope': 'student', 'profileId': Api.profileIds.first});
      Api.setScopedToken(d['accessToken']);
      if (to == UiIdentity.student) {
        await ChildMode.enable(d['accessToken']); // 开机直达 + 守护仅孩子设备生效
        await Kiosk.enable();
      } else {
        await ChildMode.disable();  // 家长端解除强管控
        await Kiosk.disable();
      }
      if (!mounted) return;
      Navigator.pop(context);
      Navigator.pushReplacementNamed(context, to == UiIdentity.student ? '/gate' : '/parent');
    } on ApiException catch (e) {
      setState(() => error = e.message);
    }
  }

  Future<void> _logout() async {
    await ChildMode.disable();
    await Kiosk.disable();
    if (!mounted) return;
    Navigator.pop(context);
    Navigator.pushReplacementNamed(context, '/');
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Container(
      decoration: const BoxDecoration(
        color: Ui.bg,
        borderRadius: BorderRadius.vertical(top: Radius.circular(Ui.rBrand)),
      ),
      padding: EdgeInsets.fromLTRB(24, 20, 24, 24 + bottom),
      child: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Center(child: Container(width: 40, height: 4,
              decoration: BoxDecoration(color: Ui.divider, borderRadius: BorderRadius.circular(2)))),
          const SizedBox(height: 16),
          const Text('家长入口', style: Ui.title),
          const SizedBox(height: 6),
          Text(widget.target == null
              ? '验证家长操作密码后，可切换身份、退出登录或管理守护设置'
              : '切换到「${widget.target == UiIdentity.parent ? '家长端' : '学生端'}」需验证家长操作密码',
              style: Ui.caption),
          const SizedBox(height: 16),
          // 密码默认提示：未设置时明确告知首次设置入口
          UiBanner(
            message: hasPassword == false
                ? '尚未设置家长操作密码。请在下方「设置 / 修改操作密码」中创建 6 位数字密码，设置后即可用于身份切换与退出。'
                : '请输入家长设置的 6 位操作密码（由家长在家长端设置或修改）。',
            tone: UiTone.info,
          ),
          const SizedBox(height: 16),
          if (!verified) ...[
            const UiLabel('操作密码'),
            const SizedBox(height: 8),
            UiInput(controller: pwd, hint: '请输入 6 位操作密码', icon: Icons.pin_outlined,
                keyboardType: TextInputType.number, textInputAction: TextInputAction.done,
                obscure: true, maxLength: 6, hasError: error != null),
            const SizedBox(height: 12),
            UiButton(label: '验证', onPressed: loading ? null : _verify, loading: loading,
                icon: Icons.verified_user_outlined),
          ] else ...[
            const UiBanner(message: '验证通过', tone: UiTone.success),
            const SizedBox(height: 14),
            if (widget.target != null)
              UiButton(label: '确认切换到 ${widget.target == UiIdentity.parent ? '家长端' : '学生端'}',
                  onPressed: () => _switchTo(widget.target!), icon: Icons.swap_horiz_rounded)
            else
              Row(children: [
                Expanded(child: UiButton(label: '切换到家长端', primary: false,
                    onPressed: widget.current == UiIdentity.parent ? null : () => _switchTo(UiIdentity.parent))),
                const SizedBox(width: 10),
                Expanded(child: UiButton(label: '切换到学生端', primary: false,
                    onPressed: widget.current == UiIdentity.student ? null : () => _switchTo(UiIdentity.student))),
              ]),
            const SizedBox(height: 10),
            UiButton(label: '退出登录', primary: false, onPressed: _logout, icon: Icons.logout_rounded),
          ],
          if (error != null) ...[
            const SizedBox(height: 12),
            UiBanner(message: error!, tone: UiTone.error),
          ],
          if (notice != null && error == null) ...[
            const SizedBox(height: 12),
            UiBanner(message: notice!, tone: UiTone.success),
          ],
          const SizedBox(height: 16),
          const Divider(color: Ui.divider, height: 1),
          const SizedBox(height: 12),
          // 家长密码设置 / 修改入口（始终可见）
          OutlinedButton.icon(
            icon: const Icon(Icons.password_rounded, size: 18),
            label: Text(hasPassword == true ? '修改操作密码' : '设置操作密码'),
            style: OutlinedButton.styleFrom(
              foregroundColor: Ui.primary,
              side: const BorderSide(color: Ui.fieldBorder),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
              minimumSize: const Size.fromHeight(48),
            ),
            onPressed: () => setState(() => showPwdSettings = !showPwdSettings),
          ),
          if (showPwdSettings) ...[
            const SizedBox(height: 14),
            if (hasPassword == true) ...[
              const UiLabel('原操作密码'),
              const SizedBox(height: 8),
              UiInput(controller: oldPwd, hint: '请输入原 6 位密码', icon: Icons.lock_outline,
                  keyboardType: TextInputType.number, textInputAction: TextInputAction.next,
                  obscure: true, maxLength: 6),
              const SizedBox(height: 12),
            ],
            const UiLabel('新操作密码（6 位数字）'),
            const SizedBox(height: 8),
            UiInput(controller: newPwd, hint: '请输入新 6 位密码', icon: Icons.lock_reset_outlined,
                keyboardType: TextInputType.number, textInputAction: TextInputAction.done,
                obscure: true, maxLength: 6),
            const SizedBox(height: 12),
            UiButton(label: hasPassword == true ? '确认修改' : '确认设置',
                onPressed: loading ? null : _savePassword, loading: loading),
          ],
          const SizedBox(height: 12),
          // HOME 键守护的一次性系统授权入口
          OutlinedButton.icon(
            icon: const Icon(Icons.home_work_outlined, size: 18),
            label: const Text('系统守护授权（设为默认桌面）'),
            style: OutlinedButton.styleFrom(
              foregroundColor: Ui.primary,
              side: const BorderSide(color: Ui.fieldBorder),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
              minimumSize: const Size.fromHeight(48),
            ),
            onPressed: () => Kiosk.openHomeSettings(),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            icon: const Icon(Icons.notifications_active_outlined, size: 18),
            label: const Text('开启守护通知（切离提醒）'),
            style: OutlinedButton.styleFrom(
              foregroundColor: Ui.primary,
              side: const BorderSide(color: Ui.fieldBorder),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Ui.rControl)),
              minimumSize: const Size.fromHeight(48),
            ),
            onPressed: () => Kiosk.openNotificationSettings(),
          ),
          const SizedBox(height: 8),
        ]),
      ),
    );
  }
}

/// 左上角明确入口按钮（所有主要页面共用）
Widget uiParentAccessButton(BuildContext context, UiIdentity current) => Padding(
      padding: const EdgeInsets.only(left: 8),
      child: IconButton(
        icon: Container(
          width: 34, height: 34,
          decoration: BoxDecoration(
            color: Ui.primarySoft,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: Ui.fieldBorder),
          ),
          child: const Icon(Icons.admin_panel_settings_outlined, size: 20, color: Ui.primary),
        ),
        tooltip: '家长入口（密码 / 切换身份 / 守护设置）',
        onPressed: () => openParentAccess(context, current: current),
      ),
    );
