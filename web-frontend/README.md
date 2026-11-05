# web-frontend — 「学习解锁」Web 前端（React + Vite，3100）

> 对应：《学习解锁Web管理后台-技术架构文档-v1.0.md》；后台 PRD 页面清单 W1~W10
> **首次初始化**：`npx create-umi@latest`（选 Ant Design Pro 模板）或 `npm create vite@latest web-frontend -- --template react-ts` 后按 Pro 规范组织；生成后把本骨架合并进去。

## 目录结构（对应后台 PRD 页面清单）

```
src/
├── access.ts           # 前端RBAC：permissionCode → 路由/按钮可见性(后端为最终裁判)
├── pages/
│   ├── workbench/      # W1 工作台（审核/退款/差异单/告警四类待办红点）
│   ├── user/           # W2 用户管理（脱敏+明文查看二次验证）
│   ├── order/          # W3 订单与退款审批（双人复核）
│   ├── reconcile/      # W4 对账中心 + 资损告警
│   ├── bank/           # W5 题库运营（批量导入/上下架/紧急下架）
│   ├── review/         # W6 审核工单（家长题目人工复核）
│   ├── ticket/         # W7 客服工单（申诉裁决/SLA）
│   ├── dashboard/      # W8 数据看板（宽表查询，只读从库）
│   ├── setting/        # W9 系统配置（价格双人复核/灰度开关/词库）
│   └── iam/            # W10 权限管理 + 审计日志检索
├── components/         # DualReview双人复核、SensitiveText脱敏、AsyncExport
└── services/           # API 封装（对接 web-backend（8081，vite /api 代理））
```

## 关键约定

1. 所有写操作接口必须在服务端审计（前端不承担安全职责，access.ts 仅做展示控制）；
2. 敏感字段默认脱敏渲染，「查看明文」走 TOTP 二次验证组件；
3. 大导出走异步任务（提交 → MQ → OSS → 通知下载），禁止前端同步导出大表；
4. 批量操作必须带影响范围预览弹窗 + 分批 500/批提交。
