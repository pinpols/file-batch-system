# API 文档索引

这里收纳前后端对接所需的接口协议、OpenAPI 规范和示例。

## 目录分工

- [console-api-protocol.md](./console-api-protocol.md)：人看的控制台接口总协议
- [console-api.openapi.yaml](./console-api.openapi.yaml)：机器可读的控制台 OpenAPI 规范
- 协议正文里已经包含权限、JWT 登录、tenant、幂等、分页、错误码和兼容性规则
- 协议正文还包含浏览器安全响应头、文本安全规则和前端渲染约束
- 控制台侧边栏菜单树与页面可见角色说明见 [docs/architecture/console-sidebar-menu-tree.md](../architecture/console-sidebar-menu-tree.md)
- 协议正文还包含 Excel 配置维护规则，包括 `upload / preview / apply / export` 约束、白名单字段和只读记录边界
- 协议正文里的 Excel 配置维护规则已覆盖 `file template config`、`file channel config`、`workflow definition / node / edge` 和 `job definition` 的安全字段
- Excel 模板约束也已写入协议正文：主 sheet 冻结表头、必填高亮、枚举下拉、示例值、说明页和 preview 校验反馈
- 只导出报表规则也已写入协议正文：`config release`、`config change log`、`secret version`、`audit log`、`scheduler snapshot/history`、`worker registry` 和 `outbox retry/delivery`
- 协议正文和 OpenAPI 已补上统一的响应封装、分页模型和共通 header 约定

## 推荐用法

1. 前端先看 `console-api-protocol.md`，确认权限、header 和接口范围
2. 前端代码生成和 mock 优先使用 `console-api.openapi.yaml`
3. 后端补接口时同时更新这两份文档
