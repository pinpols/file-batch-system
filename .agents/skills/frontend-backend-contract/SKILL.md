---
name: frontend-backend-contract
description: 修改或审查 Console API、OpenAPI、前端调用、导航权限、导入导出模板和前后端联动功能时使用。以后端契约为权威，并同步 paired frontend 仓库。
---

# 前后端契约治理

## 权威来源

- 后端 `/api/console/**`、OpenAPI、导入导出 schema 和权限模型是契约权威；前端当前实现不能反向定义后端语义。
- paired frontend 仓库位于 `../batch-console`。API 客户端在 `src/api`，生成类型在 `src/types/api.generated.ts`，页面、store 和导航分别在 `src/views`、`src/stores`、`src/constants/navigation.ts`。
- 变更认证、租户、权限、菜单、错误码或响应结构时，必须同时检查前端映射和用户可见文案。

## 常见联动

1. Console API 字段增删改：更新 OpenAPI、生成类型、前端 API 调用、mock/fixture 和相关页面状态。
2. 配置包/Excel 导入导出：以后端 schema 和模板导出为准，前端只做友好引导和提前校验；说明、必填、默认值、下拉、软链接和错误展示要一致。
3. Pipeline/SSE/进度：前端展示必须对应后端实际状态事件，不能用推断状态替代真实链路。
4. 菜单和权限：导航显隐、路由守卫、后端鉴权和审计日志要同向收敛。

## 验证

- 后端变更运行相应单测/IT/OpenAPI 校验；前端变更运行 typecheck、lint 和相关 e2e/spec。
- 没有实际运行 paired repo 验证时，在结论中明确“仅后端侧验证”或“前端待验证”。
- PR 中说明契约影响、兼容性、迁移方式和前端同步状态。

## 边界

不要为了前端临时展示绕过后端领域校验。对尚未实现的前端能力，可先输出契约和文档，但不要宣称用户链路已经完整。
