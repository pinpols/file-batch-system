# API 文档索引

这里收纳前后端对接所需的接口协议、OpenAPI 规范和示例。

## 目录分工

- [console-api-protocol.md](./console-api-protocol.md)：人看的控制台接口总协议
- [console-api.openapi.yaml](./console-api.openapi.yaml)：机器可读的控制台 OpenAPI 规范

## 推荐用法

1. 前端先看 `console-api-protocol.md`，确认权限、header 和接口范围
2. 前端代码生成和 mock 优先使用 `console-api.openapi.yaml`
3. 后端补接口时同时更新这两份文档
