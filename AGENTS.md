# AGENTS.md

## 配对前端仓库

前后端联调时，前端代码位于相对路径：

```
../batch-console
```

绝对路径示例：`/Users/dengchao/Downloads/batch-console`

### 关键目录

- `../batch-console/src/api` —— 前端 API 客户端（axios 封装、拦截器、SSE）
- `../batch-console/src/types/api.generated.ts` —— 由本后端 OpenAPI 生成的 TS 类型，修改接口后应重新生成
- `../batch-console/src/views` —— 各业务页面，按 `monitor/observability/scheduler/system/workflow` 等域拆分
- `../batch-console/src/stores` —— Pinia store（`auth`、`permission`、`tenant` 等）
- `../batch-console/src/constants/navigation.ts` —— 前端静态菜单定义
- `../batch-console/README.md` —— 前端本地运行说明

### 接入约定

- 新增 / 修改 Console REST API（`/api/console/**`）时，前端需要同步更新 `api.generated.ts`，调用方在 `../batch-console/src/api` 下。
- 认证载荷（`ConsoleAuthProfilePayload`）字段如需扩充（例如下发菜单），请先与前端 `src/api/auth.ts` 的 `mapProfileToUserInfo` 对齐。
- 权限/菜单模型如有调整，同步检查前端 `src/stores/permission.ts` 与 `src/constants/navigation.ts`。
