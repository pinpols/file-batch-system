# Excel 导入导出缺口分析

> 分析日期：2026-04-09

## 1. 当前已完成清单

### 1.1 可导入导出（upload -> preview -> apply + export）

| 配置对象 | Controller | Service 接口 | 实现 | Store | xlsx 模板 | Controller 测试 | Service 测试 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| file template config | Y | Y | Y | Y | Y | Y | Y |
| file channel config | Y | Y | Y | Y | Y | Y | Y |
| workflow definition | Y | Y | Y | Y | Y | Y | Y |
| job definition | Y | Y | Y | Y | Y | Y | Y |
| **alert routing** | **N** | **N** | **N** | **N** | Y (仅模板) | **N** | **N** |

### 1.2 只读导出报表（ConsoleReportExcelController）

已实现 9 个端点：config releases / secret versions / change logs / audit logs / scheduler snapshot / scheduler history / workers / outbox retries / outbox deliveries。

---

## 2. 缺口明细

### 缺口 A：alert routing / notification policy — 全栈缺失

**现状**：`alert-routing-notification-policy-template.xlsx` 模板已就绪，gallery 文档已规划字段，但无任何 Java 代码。

**需补全**：
1. DB migration：`batch.alert_routing_config` 建表
2. `AlertRoutingConfigMapper` + MyBatis XML
3. `AlertRoutingConfigUpsertParam`
4. `AlertRoutingExcelImportStore` 接口 + `InMemoryAlertRoutingExcelImportStore`
5. `ConsoleAlertRoutingExcelApplicationService` 接口
6. `DefaultConsoleAlertRoutingExcelApplicationService` 实现
7. `ConsoleAlertRoutingExcelController`（export / upload / preview / apply）
8. Query / Request / Response 全套 DTO
9. `ConsoleAlertRoutingExcelControllerTest`
10. `DefaultConsoleAlertRoutingExcelApplicationServiceTest`

### 缺口 B：空白模板下载端点

**现状**：所有可导入 Controller 只有 `GET /export`（导出已有数据），无 `GET /template`（下载空白模板）。新租户无数据时无法获取填写模板。

**需补全**：为 5 个可导入对象各增加 `GET /template` 端点：
- `ConsoleFileTemplateExcelController`
- `ConsoleFileChannelExcelController`
- `ConsoleWorkflowExcelController`
- `ConsoleJobDefinitionExcelController`
- `ConsoleAlertRoutingExcelController`（新建时一并加入）

### 缺口 C：Store 层一致性

**现状**：4 个已有导入对象均已有独立 Store 接口 + InMemory 实现，模式一致。alert routing 需沿用同一模式。

**结论**：Store 层无不一致问题，仅 alert routing 需新建。

---

## 3. 修复计划

| 序号 | 修复项 | 涉及模块 |
|---|---|---|
| 1 | alert routing 建表迁移 | batch-orchestrator |
| 2 | alert routing Mapper + XML | batch-console-api |
| 3 | alert routing Store | batch-console-api |
| 4 | alert routing Excel 全套（Controller + Service + Impl + DTO） | batch-console-api |
| 5 | alert routing 测试 | batch-console-api |
| 6 | 5 个 Controller 各加 GET /template | batch-console-api |
| 7 | 更新 gallery 文档 | docs |
