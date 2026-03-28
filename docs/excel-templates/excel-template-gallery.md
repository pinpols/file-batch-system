# Excel Template Gallery

这是一份给控制台配置 Excel 维护用的模板预览稿，不是数据库导出稿。

真实样例文件见 [README.md](./README.md)。

约束来自 [dialog-7.md](./dialog-7.md)：
- 主 sheet 面向用户填写
- 必须有冻结表头、必填高亮、下拉枚举、示例值、自动列宽
- 复杂字段靠说明页、字典页、预览校验页辅助
- 导入必须先 `upload -> preview -> apply`
- 数据页必须放在第一个 sheet，便于直接作为上传模板
- 运行时导出的 Excel 也按同一 workbook 结构生成，用户可以导出后修改再导入

## 统一 Workbook 结构

每个可导入模板都建议包含 3 到 4 个 sheet：

- `DATA` / `DEFINITION`
  - 用户真正编辑的主表，且必须在第一个 sheet
- `README`
  - 说明用途、导入顺序、注意事项
- `DICT`
  - 枚举值、状态值、可选值
- `VALIDATION`
  - 预览阶段回写的错误定位结果

通用视觉规则：
- 首行冻结
- 表头固定样式
- 必填列浅黄底
- 只读列灰底
- 枚举列支持下拉
- JSON 类字段使用浅灰底并给样例
- 主表顶部可加一行“填写提示”

---

## 1. `file template config`

这是最优先做的模板。字段多、规则多，且当前控制台已经有实际 Excel 导入实现，模板必须跟解析字段一致。

### 对齐原则

- `DATA` 页列名与导入解析器完全一致
- `id`、`created_by`、`updated_by`、`created_at`、`updated_at` 不开放给用户填
- `tenant_id` 由当前租户预填，允许留空后自动回填

### 推荐样例文件

- [file-template-config-template.xlsx](./file-template-config-template.xlsx)

---

## 2. `file channel config`

通道配置和 endpoint、认证参数天然表格化，适合 Excel 维护。

### 对齐原则

- 列名对齐 `batch.file_channel_config`
- `config_json` 直接写 JSON 文本
- `tenant_id`、`enabled`、`timeout_seconds` 都要保留

### 推荐样例文件

- [file-channel-config-template.xlsx](./file-channel-config-template.xlsx)

---

## 3. `workflow definition / node / edge`

这一组适合导出，导入要谨慎。建议先看成“迁移模板”，不是日常编辑模板。

### 对齐原则

- `WORKFLOW_DEFINITION` 对齐 `batch.workflow_definition`
- `WORKFLOW_NODE` / `WORKFLOW_EDGE` 使用 `workflow_code + workflow_version` 作为人工可读关联键，系统内部再解析 `workflow_definition_id`
- 节点和边的完整性必须在 preview 阶段校验

### 推荐样例文件

- [workflow-maintenance-template.xlsx](./workflow-maintenance-template.xlsx)

---

## 4. `job definition` 安全字段

这个模板只开放白名单字段，不开放执行器内部实现细节。

### 对齐原则

- `DATA` 页保留可运维修改的字段：`tenant_id`、`job_code`、`job_name`、`job_type`、`biz_type`、`schedule_type`、`schedule_expr`、`timezone`、`priority`、`queue_code`、`worker_group`、`calendar_code`、`window_code`、`trigger_mode`、`dag_enabled`、`shard_strategy`、`retry_policy`、`retry_max_count`、`timeout_seconds`、`param_schema`、`default_params`、`enabled`、`description`
- `SYSTEM` 页列出系统控制字段：`execution_handler`、`version`、`created_by`、`updated_by`、`created_at`、`updated_at`
- `priority`、`trigger_mode`、`dag_enabled`、`shard_strategy` 都按实际设计保留
- `execution_handler`、`version`、审计字段不作为用户主填项

### 推荐样例文件

- [job-definition-template.xlsx](./job-definition-template.xlsx)

---

## 5. `alert routing / notification policy`

这类配置对齐当前 observability 里的 Alertmanager 路由和 receiver 约定。

### 对齐原则

- `DATA` 页使用 `tenant_id`、`route_code`、`route_name`、`team`、`alert_group`、`severity`、`receiver`、`group_by`、`group_wait_seconds`、`group_interval_seconds`、`repeat_interval_seconds`、`enabled`、`description`
- `severity`、`team`、`alert_group`、`receiver` 对齐告警路由标签
- `group_by`、`group_wait_seconds`、`group_interval_seconds`、`repeat_interval_seconds` 对齐 Alertmanager route
- 这是可编辑模板，不是宏驱动表格

### 推荐样例文件

- [alert-routing-notification-policy-template.xlsx](./alert-routing-notification-policy-template.xlsx)

---

## 只导出对象

下面这些不做 Excel 回灌，只做报表导出：

- `config release`
- `config change log`
- `secret version`
- `audit log`
- `scheduler snapshot/history`
- `worker registry`
- `outbox retry/delivery`

原因：
- 这些是事实记录，不是配置源
- 导入会破坏审计和运行态边界

---

## 你现在可以先看的结论

- `file template config` 已与实际导入解析字段对齐
- `file channel config` 次之
- `workflow definition / node / edge` 适合迁移，不适合日常编辑
- `job definition` 只开放安全字段，并把系统字段单独说明
- `alert routing / notification policy` 对齐 observability 里的 Alertmanager 路由约定
- 运行记录类只导出，不导入
