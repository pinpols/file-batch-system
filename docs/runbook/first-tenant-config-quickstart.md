# 第一个租户配置 · 手把手 quickstart

> 给**配置维护者 / 新人**:从零把一个租户的「CSV 导入」和「导出」跑起来。全程**可抄**,不用先读完设计文档。
> 配套:不想手写 JSON?用 Excel 配置模板(本文 §4)。填错了怎么办?看 §6。

---

## 0. 心智模型(先记这张图)

```
租户(tenant) ──持有──► 模板(file_template_config) ──引用──► 渠道(channel) / 队列(queue)
     │                        │
     │                        └─ template_type = IMPORT / EXPORT / PROCESS / DISPATCH
     └─ 一键建租户时可从 default 租户复制一套模板骨架(再填实)
```

- **租户**:隔离单元。建好后默认拿到一套**空骨架模板**(字段映射是空的,要你填)。
- **模板**:一个导入/导出任务"长什么样"——文件格式、字段怎么映射、落哪张表 / 用哪条 SQL。**90% 的配置工作在这。**
- **渠道 / 队列**:导出投递目标(SFTP/API)、调度队列。最简单的"导入入库 + 导出落文件"用不到渠道。

**跑通一个导入只需要填好一个 IMPORT 模板;跑通一个导出只需要填好一个 EXPORT 模板。** 先把这两件事做完。

---

## 1. 建租户(顺手复制默认配置骨架)

`POST /api/console/tenants`,带 `initConfigFrom=default` 让系统**建租户 + 复制 default 模板租户的配置骨架 + 立刻做就绪自检**,一次返回:

```bash
curl -X POST /api/console/tenants \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{
    "tenantId": "acme",
    "tenantName": "ACME Corp",
    "adminUsername": "acme-admin",
    "adminPassword": "<首登后会强制改密>",
    "initConfigFrom": "default",
    "initMode": "SKIP_EXISTING"
  }'
```

返回里直接带 `readiness`(见下一步)。`initConfigFrom` 不填 = 不复制(空租户);`initMode` 默认 `SKIP_EXISTING`(不覆盖已存在配置)。

> 内置/新建账号**首次登录会被强制改密**(`must_change_password`),改密接口 `POST /api/console/auth/change-password`。

---

## 2. 看就绪自检:系统告诉你「还缺什么、去哪填」

`GET /api/console/tenants/acme/readiness`:

```jsonc
{
  "ready": false,
  "blocking": [
    {
      "item": "template",
      "reason": "template IMP-CUSTOMER-CSV missing field_mappings / query_param_schema",
      "ref": "IMP-CUSTOMER-CSV",
      "hint": "在配置模板 file_template_config sheet 填 field_mappings 和 query_param_schema,参考『四类Worker示例』",
      "docRef": "docs/runbook/first-tenant-config-quickstart.md#3-填模板核心"
    }
  ],
  "warnings": []
}
```

- `blocking` 非空 = **跑必失败**,挨个填掉。`hint`/`docRef` 告诉你**去哪填、抄哪份样例**。
- `warnings` = 可疑但能跑,可暂缓。
- 复制来的骨架模板 `field_mappings` / `query_param_schema` / `default_query_sql` 都是空的——这是**正常**,下一步就填它。

---

## 3. 填模板(核心)

下面两份是**可直接抄改**的真实样例(取自 e2e 金标准 fixture,字段名与 worker 实际解析一致)。把里面的表名/列名换成你的业务即可。

### 3a. CSV 导入 → 入库(最常见)

一个 IMPORT 模板要填三块:`field_mappings`(文件列→校验)、`validation_rule_set`(质量规则)、`query_param_schema.jdbcMappedImport`(**落哪张表、哪些列、冲突键**——全系统最关键的字段)。

```jsonc
// field_mappings:文件里有哪些列、各自类型/校验。注意 import 用 "targetColumn"。
[
  {"name":"customerNo",   "targetColumn":"customer_no",   "type":"STRING",  "required":true,  "maxLength":32},
  {"name":"customerName", "targetColumn":"customer_name", "type":"STRING",  "required":true,  "maxLength":128},
  {"name":"customerType", "targetColumn":"customer_type", "type":"STRING",  "required":true,  "allowedValues":["PERSONAL","CORPORATE"]},
  {"name":"creditLimit",  "targetColumn":"credit_limit",  "type":"DECIMAL", "required":true,  "minValue":0},
  {"name":"email",        "targetColumn":"email",         "type":"EMAIL",   "required":false, "maxLength":256},
  {"name":"status",       "targetColumn":"status",        "type":"STRING",  "required":true,  "allowedValues":["ACTIVE","INACTIVE","SUSPENDED"]},
  {"name":"openDate",     "targetColumn":"open_date",     "type":"DATE",    "required":true,  "format":"yyyy-MM-dd"}
]
```

```jsonc
// validation_rule_set:整体质量门槛。坏行率超阈值整批失败;重复键检测。
{"maxErrorRate":0.05, "stopOnFirstError":false,
 "duplicateKeyCheck":{"enabled":true, "keys":["customerNo"]}}
```

```jsonc
// query_param_schema:写入数据库映射。配合上面的 field_mappings,这里可极简。
{"jdbcMappedImport":{
  "schema":"biz", "table":"customer_account", "tenantColumn":"tenant_id",
  // ① columnMappings 可整段省略:留空时按 field_mappings 自动推断——
  //    to 取 field_mappings.targetColumn;没写 targetColumn 时对 name 做大小写/下划线归一
  //    (customerNo→customer_no)。只有"文件列名和表列名真不一样"才写差异项,例如文件叫
  //    phone、表列叫 mobile_no 时写:  "columnMappings":[{"from":"phone","to":"mobile_no"}]
  //    想"校验但不入库"的列,在 field_mappings 给它加 "persist":false。
  // ② conflictColumns:ON CONFLICT 幂等键(重跑不重复)。漏写 tenant_id 会被系统自动补到最前。
  "conflictColumns":["customer_no"],
  // ③ standardAuditBindings:true → 一键写入标准审计列(source_file_name / source_batch_no /
  //    source_trace_id / created_by / updated_by),免逐条手写 systemBindings;
  //    要自定义就写 systemBindings(显式项覆盖标准默认)。
  "standardAuditBindings": true
}}
```

> 字段速查:`schema/table`=落哪张业务表;`tenantColumn`=租户隔离列(几乎都是 `tenant_id`);`columnMappings`=**可省**(从 field_mappings 推断,只写差异列);`conflictColumns`=幂等键(自动补 tenant_id);`standardAuditBindings:true`=一键审计列。
>
> 注:上传配置包时**预览期**会校验 `jdbcMappedImport` 必含 `table`/`tenantColumn`、`field_mappings` 每项必含 `name`、CRON 作业必含合法 `schedule_expr`、DELIMITED 模板必含 `delimiter`、导出 SQL 禁 `SELECT *`——异常配置不会留到运行期才失败。

### 3b. 导出 → 文件(CSV / Excel)

一个 EXPORT 模板填两块:`default_query_sql`(取数 SQL,**用 `:tenantId` 命名参数**)、`field_mappings`(**注意 export 用 "sourceColumn" + "header"**,和 import 不同)。

```sql
-- default_query_sql::tenantId / :status 是命名参数(运行时绑定);别和文件命名的 ${bizDate} 混淆。
SELECT customer_no, customer_name, customer_type, credit_limit, currency_code, email, status, open_date
FROM biz.customer_account
WHERE tenant_id = :tenantId AND (:status IS NULL OR status = :status)
```

```jsonc
// field_mappings(export):sourceColumn=SQL 结果列;header=输出表头;DECIMAL/DATE 可给 format。
[
  {"name":"customerNo",   "sourceColumn":"customer_no",   "type":"STRING",  "header":"客户号"},
  {"name":"customerName", "sourceColumn":"customer_name", "type":"STRING",  "header":"客户名"},
  {"name":"creditLimit",  "sourceColumn":"credit_limit",  "type":"DECIMAL", "header":"授信额度", "format":"#,##0.00"},
  {"name":"openDate",     "sourceColumn":"open_date",     "type":"DATE",    "header":"开户日期", "format":"yyyy-MM-dd"}
]
```

```jsonc
// query_param_schema(export):声明 SQL 命名参数。
{"type":"object","properties":{
  "tenantId":{"type":"string","required":true},
  "status":{"type":"string","required":false}}}
```

- 导出 **Excel**:把模板 `file_format_type` 设 `EXCEL`。新增可选项:`field_mappings[*].type=NUMBER/DATE`(让数字/日期在 Excel 里是真类型可求和/筛选)、`rows_per_sheet`(超大结果集自动拆 sheet)、`header_style`(表头加粗/列宽/冻结)。不填 = 全文本单 sheet(向后兼容)。

> **import vs export 的 field_mappings 是两套结构**:import 用 `targetColumn`(文件→表列),export 用 `sourceColumn`+`header`(SQL 列→表头)。别抄错。

---

## 4. 不想手写 JSON?用 Excel 配置模板

`GET /api/console/config/tenant-package/excel/template` 下载一个**带引导的空白工作簿**:

- 「**字段说明**」sheet:每个字段的 必填★ / 类型 / 可选值枚举 / 说明 / 示例 / **填写示例**(完整可抄片段)/ 适用 Worker;枚举列是**下拉**。
- 「**四类Worker示例**」sheet:IMPORT/EXPORT/PROCESS/DISPATCH 各一份**填好的整行范例**,照着改。
- 「**依赖说明**」sheet:job→template/channel/queue 引用关系 + DB fallback 规则。

填完上传:`POST .../tenant-package/excel`(upload)→ `.../preview/{token}`(**预览,一次性列出字段错/SQL 错/跨 sheet 引用错**)→ `.../apply`(确认写入)。

---

## 5. 跑起来

- **再查一次就绪**:`GET /api/console/tenants/acme/readiness`,`ready:true` 即可跑。
- **触发导入**:把文件放进对象存储的 **`ingress/<tenantId>/` 前缀**(如 `ingress/acme/customer-20260601.csv`),导入扫描器自动登记并触发 pipeline(IMPORT→PARSE→VALIDATE→LOAD)。无需手工调接口。
- **触发导出 / Job**:经触发器 / 调度跑(详见 `docs/runbook/` 触发相关 + `system-flow-overview.md`)。

---

## 6. 填错了 / 卡住了怎么办

| 症状 | 系统给你的反馈 | 去哪看 |
|---|---|---|
| 漏填关键字段 | `readiness.blocking[].hint/docRef` 直接说"去哪填、抄哪份" | §2 |
| Excel 配置包填错 | upload→`preview` 一次性列出所有字段/SQL/引用错 | §4 |
| 导入有坏行 | `file_error_record` 写入数据库**带 Excel 物理行号 + 出错列**,能回原表定位 | 坏行治理 |
| 导入整列变 null | 多半是**表头名拼错**——现在 LOAD 前会校验表头存在性,fail-fast 报缺哪列 | §3a |
| 不确定某字段含义/格式 | Excel 字段说明 sheet(§4)是字段字典的**单一权威源** | §4 |

---

## 7. 下一步(进阶)

- 投递渠道(SFTP/API dispatch)、`config_json` 凭据:见 9+2 配置包设计 + `docs/runbook/biz-tenant-routing.md`(凭据隔离)。
- **各类凭据怎么存/怎么注入/谁负责**:见 [`credential-matrix.md`](./credential-matrix.md)(凭据矩阵:片级账密 / 渠道凭据 / 密码 / 内部密钥 / KMS / DB 连接一表打尽,标注 prod 强校验与上线必配项)。
- 队列 / 时间窗 / 日历调度、加密下载审批:见 `docs/design/` 数据模型 + 各 ADR。
- 完整可跑样例集:`batch-e2e-tests/src/test/resources/db/testdata/import-template-config-seed.sql` / `export-template-config-seed.sql`(全格式金标准)。
