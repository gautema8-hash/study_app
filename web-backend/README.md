# web-backend — 「学习解锁」Web 后端（Spring Boot，8081）

> 对应：《学习解锁Web管理后台-技术架构文档-v1.0.md》；仅含 Web 后台业务代码，**不依赖 app-backend 任何类**。

## 包内组成

```
src/main/java/com/xuexijiesuo/web/
├── WebAdminApplication.java    # 独立启动入口
├── OpsController.java          # 运营查询/登录/审核裁决/申诉裁决/价格配置
├── AdminAuthInterceptor.java   # RBAC 拦截器（细粒度写权限）
├── AdminWebConfig.java         # 拦截器注册
├── SecurityConfig.java         # 独立安全配置
└── WebTokenService.java        # 独立 HS256 token 签发/校验（同 JWT_SECRET 环境变量）
src/main/resources/
├── application.yml             # 8081 + 数据源 + JWT 配置
└── application-local.yml       # 本地口令（已 gitignore）
```

## 构建与运行

```bash
cd web-backend && ../mvnw spring-boot:run          # 独立运行 8081
cd .. && ./mvnw clean compile                       # 根聚合一次编译两个后端包
```

## 依赖边界

- 不引用 `app-backend` 的 Java 类；运营查询直接读共享 MySQL（数据层共享，代码不交叉）。
- `web-frontend`（3100）的 `/api` 代理固定指向本包 8081。
- `app-frontend` 固定指向 `app-backend` 8080，两端互不感知。
