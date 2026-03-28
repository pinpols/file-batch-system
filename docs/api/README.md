# API 文档索引

这里收纳前后端对接所需的接口协议、OpenAPI 规范和示例。

## 目录分工

- [console-api-protocol.md](./console-api-protocol.md)：人看的控制台接口总协议
- [console-api.openapi.yaml](./console-api.openapi.yaml)：机器可读的控制台 OpenAPI 规范
- 协议正文里已经包含权限、JWT 登录、tenant、幂等、分页、错误码和兼容性规则
- 协议正文还包含浏览器安全响应头、文本安全规则和前端渲染约束
- 协议正文和 OpenAPI 已补上统一的响应封装、分页模型和共通 header 约定

## 推荐用法

1. 前端先看 `console-api-protocol.md`，确认权限、header 和接口范围
2. 前端代码生成和 mock 优先使用 `console-api.openapi.yaml`
3. 后端补接口时同时更新这两份文档
