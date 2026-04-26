# API 文档索引

前后端契约：Console API 的人读协议 + 机读 OpenAPI。

> **CLAUDE.md 硬约束**：改 `batch-console-api` 控制层必须同步更新这两份文件，否则 PR 拒收。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [console-api-protocol.md](./console-api-protocol.md) | 人读协议总文档：权限 / JWT / tenant / 幂等 / 分页 / 错误码 / 兼容性 / Excel 配置维护 / 安全响应头 | 第一次对接 / 加新端点前 |
| 02 | [console-api.openapi.yaml](./console-api.openapi.yaml) | 机读 OpenAPI 3.0 规范（前端代码生成 + mock 用） | 前端集成 |

## 协议覆盖范围（在 01 协议正文中）

- **基础设施**：权限 / JWT 登录 / tenant 解析 / 幂等键 / 分页 / 错误码 / 版本兼容
- **安全**：浏览器响应头、文本安全规则、前端渲染约束
- **统一规范**：响应封装 / 分页模型 / common headers
- **Excel 配置维护**（10 类）：file template / file channel / workflow / job definition / alert routing / batch window / business calendar / pipeline definition / resource queue / tenant quota policy
- **Excel 模板约束**：主 sheet 冻结表头 / 必填高亮 / 枚举下拉 / 示例值 / 说明页 / `preview workbook` 的 `VALIDATION` 明细 / 单元格批注反馈
- **只导出报表**：config release / config change log / secret version / audit log / scheduler snapshot+history / worker registry / outbox retry+delivery

## 工作流

```
前端先看 01 协议   →   确认权限 + header + 接口范围
       ↓
前端用 02 OpenAPI  →  代码生成 / mock
       ↓
后端改 controller  →  必须同步更新 01 + 02（CLAUDE.md 硬约束）
```

## 相关入口

| 主题 | 文档 |
|---|---|
| 控制台菜单树 + 角色可见性 | [`../design/console-sidebar-menu-tree.md`](../design/console-sidebar-menu-tree.md) |
| Console-API 读写分离 | [`../runbook/read-replica.md`](../runbook/read-replica.md) |
| Console-API gap 分析 | [`../design/api-gap-analysis.md`](../design/api-gap-analysis.md) |
