# app-backend — 「学习解锁」App 后端（Spring Boot，8080）

> 对应：《学习解锁App-技术架构文档-v1.0.md》§3；本包**仅含 App 端业务域**，Web 后台接口已拆至 `web-backend/`
> 技术栈：Java 17（建议升 21）+ Spring Boot 3 + MyBatis-Plus + Redis 7 + RocketMQ + XXL-Job + MySQL 8.0 主从
> **首次初始化**：用 [start.spring.io](https://start.spring.io) 生成 Spring Boot 3 项目（Maven / Java 17 / 依赖：Web、Security、Validation、MyBatis、Redis、Actuator），将生成结构合并到本目录（pom.xml 放本目录根）。

## 目录结构（模块化单体，模块间只经接口调用）

```
src/main/java/com/xuexijiesuo/
├── common/        # 统一响应{code,msg,data}、错误码分段(1xxx账号/2xxx题库/3xxx解锁/4xxx支付/5xxx后台)
├── config/        # 安全(JWT/签名校验)、Redis、MQ、数据源(主从读写分离)、审计AOP
├── account/       # 微信OAuth/手机号登录、家庭账号、设备绑定与配额(FR-31~37)
├── bank/          # 题库CRUD/批量导入/回收站/内容机审回调(FR-01~06)
├── quiz/          # 出题引擎(能力值+权重+布隆去重)、离线题包(FR-07/36)
├── grading/       # 服务端唯一判分、异常快答判定、幂等(FR-12)
├── control/       # 解锁会话状态机、免锁时段、紧急解锁、fail-open(FR-12~17)
├── risk/          # 威胁检测T-01~T-08、风控事件、家长告警(FR-13/14)
├── member/        # 会员/订单/支付回调补单/退款/对账/资损监控(FR-25~30，资金三件套)
├── adreward/      # 广告回调验签、幂等发奖、单日上限(FR-27)
├── notify/        # 厂商推送聚合、模板管理

```

## 关键约定（红线，违反即打回）

1. **金额以「分」为整型存储计算**，禁止浮点（FR-28）；
2. 发货/判分/发奖幂等：DB 唯一索引 + Redis SETNX 双保险（技术架构 §3.3）；
3. 客户端传值禁止作为扣款/判分依据——全部服务端计算；
4. 敏感字段（手机号/实名）应用层加密存储，日志禁明文 PII；
5. 每模块 DB 表按模块域拆分 schema，预留服务拆分边界。

## 本地运行前置

MySQL 8.0.44（本机已装）+ Redis（本机 6379 已运行）→ 执行 `sql/` 下建表脚本 → `mvn spring-boot:run`
