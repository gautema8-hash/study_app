# 学习解锁（Study Unlock）

一款面向 K12 家庭的「先学习、后娱乐」管控应用：孩子端需完成既定学习任务（答题闯关）才能解锁娱乐时长；家长端可配置学习门槛、查看进度与资金激励，并受一套轻量但可落地的防绕过机制守护。

本仓库采用 **前后端分离 + 多包独立** 的工程结构：App（学生/家长端）与 Web 管理后台各自拥有独立的前端包与后端包，依赖边界清晰，可分别构建、运行与部署。

---

## 一、技术架构概览

```
                          ┌─────────────────────────────┐
   孩子/家长（手机）        │         app-frontend         │  Flutter 客户端
   ───────────────────────▶│  学生端 + 家长端 + 原生守护   │  (Android 强管控)
                          └──────────────┬──────────────┘
                                         │  HTTP /api  →  :8080
                          ┌──────────────▼──────────────┐
                          │         app-backend          │  Spring Boot 8080
                          │ 账号/题库/判分/会话/风控/资金/ │  MySQL + Redis
                          │ 激励/家长操作密码             │
                          └─────────────────────────────┘

   运营/管理员（浏览器）     ┌─────────────────────────────┐
   ───────────────────────▶│         web-frontend         │  React + Vite 3100
                          └──────────────┬──────────────┘
                                         │  /api 代理  →  :8081
                          ┌──────────────▼──────────────┐
                          │         web-backend          │  Spring Boot 8081
                          │ 运营查询/登录/RBAC/审核/申诉/ │  MySQL（仅自身依赖）
                          │ 价格配置（OpsController）     │
                          └─────────────────────────────┘

   共享基础设施：MySQL（两后端共享数据源，但代码不交叉）· Redis（仅 app-backend）·
                scripts/（自动化测试与联调）· sql/（建库脚本）· docs/（设计文档）
```

## 二、包结构

| 包 | 职责 | 构建入口 | 端口 / 依赖边界 |
|----|------|----------|-----------------|
| `app-frontend/` | Flutter 客户端：学生端、家长端、Android 原生守护（LockTask / 通知 / HOME 拉回） | `pubspec.yaml` + `android/` `ios/` | 固定请求 `app-backend` :8080；不感知 Web 端 |
| `app-backend/` | App 业务域：账号、题库判分、会话管控、风控、资金、激励、家长操作密码 | `pom.xml`（Spring Boot） | **:8080**，MySQL + Redis + Security |
| `web-frontend/` | React + Vite 管理后台：工作台、用户、订单、对账、题库、审核、申诉、看板、配置、权限 | `package.json` + `vite.config.ts` | dev :3100，`/api` 代理固定指向 `web-backend` :8081 |
| `web-backend/` | 运营后台接口：`OpsController` + RBAC 拦截器 + 独立 `SecurityConfig`/`WebTokenService` | `pom.xml`（Spring Boot） | **:8081**，仅 MySQL，**不依赖 `app-backend` 任何类** |

- **根 `pom.xml`** 仅做 Maven 聚合（`packaging=pom` + `modules`），不含任何业务代码；`mvnw` / `.mvn` 已上移到根目录。
- `sql/` `scripts/` `docs/` `deploy/` 属于跨端基础设施资产，不属于任何端包。

## 三、端口与配置约定

| 服务 | 端口 | 说明 |
|------|------|------|
| app-backend | 8080 | App 业务 API 基址 |
| web-backend | 8081 | Web 运营 API 基址 |
| web-frontend (dev) | 3100 | Vite dev server，`/api` → 8081 |

- 本地口令与敏感配置统一放在各后端 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略），通过 `SPRING_PROFILES_ACTIVE=local` 或 `SERVER__PORT` / `SERVER__HOST` 环境变量注入。
- 两后端共享同一个 MySQL 逻辑库（数据层共享），但启动类、Token 实现、安全配置各自独立，代码层不互相引用。

## 四、快速开始

### 前置依赖
- JDK 17、Maven 3.9+（仓库内已带 `mvnw`）
- Flutter 3.x（构建 `app-frontend`）
- Node.js 18+（构建 `web-frontend`）
- 本地 MySQL 8.x、Redis 5.x（按 `sql/` 脚本建库）

### 1. App 后端（8080）
```bash
cd app-backend
../mvnw spring-boot:run          # 或根目录：../mvnw -pl app-backend -am compile
```

### 2. Web 后端（8081）
```bash
cd web-backend
../mvnw spring-boot:run
```

### 3. Web 前端（3100，需 web-backend 已起）
```bash
cd web-frontend
npm install
npm run dev -- --host 127.0.0.1   # 浏览器访问 http://127.0.0.1:3100
```
默认管理员账号：`admin` / `admin123`（运营环境请务必修改）。

### 4. App 前端（Flutter）
```bash
cd app-frontend
flutter pub get
flutter run                       # 真机/模拟器
# 指定后端基址构建：flutter build apk --dart-define=API_BASE=http://<你的IP>:8080
```

### 根聚合编译两个后端
```bash
./mvnw clean compile              # 一次编译 app-backend + web-backend
```

## 五、关键功能

- **登录与身份**：手机号 + 短信验证码登录（开发模式自动回填 devCode）；登录后进入「选择身份」。
- **双向身份切换（需密码）**：学生端 ↔ 家长端均通过顶部单选控件切换，切换时校验「家长操作密码」；校验失败保持当前身份并提示，校验通过才换 scope token 与守护状态。
- **家长入口面板**：从任意主要页面的左上角入口（或长按标题）打开，提供：
  - 家长操作密码的**设置 / 修改**（未设置时给出明确默认提示，避免「要密码却没入口」）；
  - 系统守护授权入口（默认桌面 / 守护通知），用于增强 HOME 键防护。
- **HOME 键防护（多层降级）**：原生 `onUserLeaveHint` 拉回 + 守护服务 3 秒巡检 + 切离提醒通知（点击即回）+ Flutter 上报 `T-02` 风控事件并在回前台重裁决。
  > 说明：部分国产 ROM（如 HyperOS）会拒绝 DeviceOwner / 默认桌面自动设置、默认关闭通知渠道，纯代码无法 100% 拦截按 HOME 回桌面；此时 UI 明示「提醒级」并提供一次性系统授权入口，配合风控告警形成可落地闭环。
- **家长 DIY 配置**：每次题数、时长、会话有效期、每日挑战次数、过关分数线。
- **学业与激励**：题库闯关判分、勋章墙、打卡日历、资金与奖励链路。
- **内容安全**：题目/答案的合规审核与申诉裁决。
- **Web 管理后台 RBAC**：6 类角色（ADMIN / FINANCE / REVIEWER / CS / OPS / VIEWER）对写操作域的细粒度权限矩阵。

## 六、测试

仓库内 `scripts/` 覆盖自动化回归：

| 脚本 | 范围 | 结果基线 |
|------|------|----------|
| `auth_e2e.sh` `quiz_e2e.sh` `control_e2e.sh` `member_e2e.sh` `reward_e2e.sh` `review_e2e.sh` | App 端 6 大业务域 E2E | 全部通过 |
| `admin_e2e.sh` | Web 后台接口 + 6 角色 × 3 写域 RBAC 矩阵 | 39/39 通过 |
| `web_browser_e2e.mjs` | 浏览器真实渲染（Edge headless + CDP，零新依赖）：路由守卫 + 10 页渲染 | 11/11 通过 |

> 运行前请确保 `app-backend`(8080)、`web-backend`(8081)、`web-frontend`(3100) 均已启动；各 E2E 脚本支持通过环境变量 `PHONE` / `WEB_BASE` / `API_BASE` 覆盖默认值。

## 七、文档索引（`docs/`）

- 产品与执行：`学习解锁App-产品需求文档PRD-v1.0` `学习解锁App-执行计划-v1.0` `学习解锁Web管理后台-产品需求文档PRD-v1.0` `学习解锁Web管理后台-执行计划-v1.0`
- 技术与设计：`学习解锁App-技术架构文档-v1.0` `学习解锁Web管理后台-技术架构文档-v1.0` `学习解锁-认证与权限设计文档-v1.0` `学习解锁-防破解防逆向安全方案-v1.0`
- 评审与测试：`学习解锁-项目综合评估报告-v1.0` `学习解锁-可行性与完整性评审报告-v1.0` `学习解锁-孩子端强管控约束评审-v1.0` `学习解锁-真机联调测试报告-v1.0` `学习解锁-双端测试报告-v1.0`
- 本次改造：`学习解锁-包结构重构迁移说明-v1.0` `学习解锁-交互与视觉优化说明-v1.0`
- 上线准备：`学习解锁-部署上线前置准备清单-v1.0`

各端包内也有独立 `README.md`（如 `app-backend/README.md` `web-backend/README.md` `web-frontend/README.md` `app-frontend/README.md`），聚焦该包自身的构建与目录说明。

## 八、提交与分支

- 主分支：`main`
- 代码风格：Java（Spring Boot 约定）、Dart（Flutter `flutter analyze` 0 error）、TypeScript（React + Vite）。
- 跨包改动请保持依赖边界：前端不交叉引用，后端不跨包引用 Java 类；新增共享能力优先沉淀为独立模块或 `core/` 共享层。
