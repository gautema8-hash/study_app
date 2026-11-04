# app — 「学习解锁」Flutter 客户端（学生端 + 家长端单工程）

> 对应：《学习解锁App-技术架构文档-v1.0.md》§2；《学习解锁App-执行计划-v1.0.md》
> 本目录是 Flutter 工程根。**首次初始化**：在本目录执行 `flutter create --org com.xuexijiesuo --project-name xuexijiesuo_app .`（会生成 android/ios/web 等平台目录，不影响已建好的 lib 子结构，冲突文件注意合并）。

## 目录结构（与技术架构 §2.2 模块划分一致）

```
lib/
├── core/          # 网络层(Dio+HMAC签名拦截器)、本地存储(SQLCipher/MMKV)、常量、路由
├── features/
│   ├── auth/      # 微信OAuth/手机号登录、身份选择、绑定码、家长密码(FR-31~35)
│   ├── guard/     # 学生模式守护：锁定态、前台服务、强停自恢复、降级模式(FR-13/18)
│   ├── quiz/      # 出题请求、答题页(防切屏)、作答暂存补传(FR-07~12/36)
│   ├── control/   # 解锁会话状态机、倒计时、免锁时段、紧急呼叫(FR-12/17)
│   ├── reward/    # 积分/勋章/打卡/兑换——学生端只读自己数据(FR-19~23)
│   ├── parent/    # 题库管理、DIY配置、管控中心、报告、会员中心、设备管理(FR-01~06/24/25/37)
│   └── security/  # 敏感操作二次确认、异常上报(FR-14/35)
├── l10n/          # 多语言占位
└── main.dart      # 入口（flutter create 后生成）
```

## 关键约定

- **双 flavor**：`student`（不含任何广告 SDK 组件）/ `parent`（含激励视频入口）——防破解方案 §3.5 的构建变体隔离；
- 状态机唯一事实源在服务端，客户端是投影（技术架构 §2.4）；
- Android 守护逻辑用 Kotlin 原生插件（MethodChannel），iOS 管控用 Swift 插件（FamilyControls 为可选增强）；
- 每个 feature 内部按 `pages/ widgets/ controllers/ models/` 四层组织。

## 首次运行验证（W1 出口）

```bash
flutter pub get && flutter run        # 默认父 flavor 空壳
flutter doctor                        # Android toolchain 必须 √
```
