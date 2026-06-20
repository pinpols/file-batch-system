# 导入/导出列映射:默认推断 + 差异覆盖设计

> 日期:2026-06-20 · 状态:设计草案(待评审,未改码)
> 范围:`batch-worker-import` / `batch-worker-export` 的 `jdbc_mapped` 列映射配置体验
> 关联:`docs/architecture/worker-plugins.md`、`docs/runbook/first-tenant-config-quickstart.md`、ADR 范围纪律(文件交付闭环,不扩张为数据治理)

## 0. 一句话

`columnMappings`(import)/ `detailSelectColumns`(export)目前**全量必填、无任何默认**,而它们要表达的列信息**在 `field_mappings` 里已经声明过一遍**。本设计让两侧在显式列映射缺省时**从 `field_mappings` 自动推断**,其中 snake_case / 下划线 / 大小写的写法差异**默认归一化兼容**;显式配置只保留**真正名字对不上的语义差异项**,把"几十列逐列手写两遍"的负担降为零。

---

## 1. 用户痛点(本设计的触发问题)

> "导入和导出都要配置列映射吗?没有默认吗?很多列用户配置是不是不友好?"

确认成立,且 import 侧尤其严重。

---

## 2. 现状(代码实证)

### 2.1 IMPORT — `columnMappings` 全量必填,无兜底

`batch-worker-import/.../jdbc/JdbcMappedImportSpec.java:58-61`:

```java
List<ColumnMapping> mappings = parseMappings(root.get("columnMappings"));
if (mappings.isEmpty()) {
  throw new WorkerConfigException("jdbc_mapped_import.columnMappings is required");
}
```

- `ColumnMapping(from, to)`:`from` = 文件字段名,`to` = 目标表列名(`JdbcMappedImportSpec.java:33`)。
- 空 → fail-fast,**无任何**"列名一致自动映射"的退化路径。

### 2.2 EXPORT — `batchSelectColumns` / `detailSelectColumns` 全量必填

`batch-worker-export/.../jdbc/JdbcMappedExportSpec.java:48-49`:

```java
if (batchCols.isEmpty() || detailCols.isEmpty()) {
  throw new IllegalArgumentException("batchSelectColumns and detailSelectColumns are required");
}
```

- 额外硬约束:`batchSelectColumns` 必须含 `id`(detail FK join 需要,`JdbcMappedExportSpec.java:83-85`)。
- DELIMITED 表头已有三级兜底(模板 `csvColumns` → 插件 `describeDelimitedColumns()` → 首页数据行推断,`AbstractExportFormat.resolveDelimitedColumns()`),**但这只决定"表头长什么样",不决定"查哪些列"**;`detailSelectColumns` 仍必填。

### 2.3 关键洞察:列信息已在 `field_mappings` 声明过一遍

`first-tenant-config-quickstart.md` 的金标准样例显示,**同一份模板里 `field_mappings` 已经携带了列映射的全部信息**:

| 侧 | `field_mappings` 字段 | 含义 | 与列映射的关系 |
|---|---|---|---|
| IMPORT | `name` + `targetColumn` | 文件列名 → 表列名 | `columnMappings.from/to` **完全冗余** |
| EXPORT | `name` + `sourceColumn` | SQL/表列 → 输出字段 | `detailSelectColumns` = 全部 `sourceColumn` |

即 import 模板里用户已经写了 `{"name":"customerNo","targetColumn":"customer_no",...}`,却被要求在 `columnMappings` 里**再写一遍** `{"from":"customerNo","to":"customer_no"}`。这是设计上可消除的二次声明。

> `field_mappings` 的解析入口已存在:`ParseSupport.templateFieldMappings()`(`ParseSupport.java:42-58`),import 侧本就读取它做 PARSE/VALIDATE。

---

## 3. 设计目标(本次确认的四个方向)

1. **IMPORT 加列名自动映射兜底** — `columnMappings` 缺省时从 `field_mappings` 推断。
2. **EXPORT 同样补默认** — `detailSelectColumns` 缺省时从 `field_mappings.sourceColumn` 推断;`batchSelectColumns` 缺省时退化为最小集 `[id]`(+ 必要列)。
3. **显式映射只写差异项** — 显式 `columnMappings`/`detailSelectColumns` 与推断结果**合并**而非互斥,用户只需写"名字对不上"的少数列。
4. **先出设计、不改码** — 本文档即交付物。

---

## 4. 核心设计:三级推断链 + 差异覆盖

### 4.1 IMPORT `columnMappings` 解析新语义

伪逻辑(替换 `JdbcMappedImportSpec.parse` 的"空即报错"):

```
explicit   = parseMappings(root.columnMappings)          // 现有显式项
inferred   = inferFromFieldMappings(templateConfig)      // 新增:从 field_mappings 推断
effective  = merge(inferred, explicit)                   // explicit 覆盖同 from 的 inferred
if effective.isEmpty():
    throw WorkerConfigException(
      "columnMappings is required and could not be inferred from field_mappings")
```

`inferFromFieldMappings` 对每个 `field_mappings[i]` 产出一条 `ColumnMapping`,`to` 列按优先级:

1. `field_mappings[i].targetColumn`(显式声明,最高优先) →
2. `normalizeColumn(field_mappings[i].name)`(**默认归一化**,见 4.2)。

`from` 取 `field_mappings[i].name`。

> **定调(2026-06-20 用户确认)**:显式配置只该出现"名字真的对不上"的**语义差异项**(如 `phone → mobile_no`);而 snake_case / 下划线 / 大小写这类**纯写法差异是默认就做掉的**,不是"兜底失败就报错"的弱猜测。即优先级 2 是一条**有信心的默认规则**,绝大多数列走它即可零配置命中。

> **不入库的字段**:现状靠"不在 columnMappings 里"表达(如 quickstart 的 `creditLimit`)。推断后默认**全部入库**会改变语义,故引入显式排除开关:`field_mappings[i].persist:false` 的项不进 `inferred`(默认 `true`)。这样既保留"校验但不入库"能力,又不需要逐列重写。

### 4.2 列名归一化规则 `normalizeColumn`(默认能力,非兜底)

写法差异的兼容是**默认就做掉**的,目标是"`customerNo` / `customer_no` / `CustomerNo` / `CUSTOMER_NO` 这些只是写法不同的同一列,用户不必为它们写任何映射"。

归一化规则(确定、可预测、可逆向解释):

- 驼峰 → 下划线:`customerNo` → `customer_no`、`mobileNo` → `mobile_no`。
- 连续大写按边界切:`customerID` → `customer_id`、`customerHTTPUrl` → `customer_http_url`。
- 全部转小写:`CUSTOMER_NO` → `customer_no`、已是 snake 的 `customer_no` → 原样。
- 因此 `from`(文件列)与目标表列的匹配在**归一化空间里大小写不敏感、下划线/驼峰互通**。

落地约束:

- 归一化结果必须过 `JdbcMappedSqlValidator.requireIdentifier`(`[a-z][a-z0-9_]*`)+ schema 白名单,这是安全闸不退让。
- 归一化是**确定函数**,不做"相似度模糊匹配"——它把写法差异消成同一规范形,而不是猜近义。真正语义不同的列(`phone` vs `mobile_no`)归一化后仍不同,**必须**靠显式 `columnMappings` 表达,这正是"只配置明确差异"的边界。
- 因此正常路径要么命中 `targetColumn`(优先级 1),要么命中归一化(优先级 2),**不存在"猜不出就静默丢列"**;归一化结果非法时报错并指明该列需显式 `targetColumn`。

### 4.3 EXPORT 兜底

`JdbcMappedExportSpec.parse` 两条列分别处理:

- `detailSelectColumns` 缺省 → 取 `field_mappings[*].sourceColumn`(去重、保序);仍为空才报错。
- `batchSelectColumns` 缺省 → 退化为 `[id]`(满足 FK join 硬约束),如配置了 `batchNoColumn` 则为 `[id, batchNoColumn]` 去重。
- 显式值与推断值**合并**,显式优先;`batchSelectColumns` 合并后仍强制含 `id`(保留 `:83-85` 校验)。

> `sql_template_export` 路径(`default_query_sql`)本就把列写在 SQL 里、`field_mappings` 只管 `header`,**不受本设计影响**,无需 `selectColumns`。

### 4.4 合并语义(差异覆盖)统一定义

`merge(inferred, explicit)`:以 `from`(import)/列名(export)为键,**explicit 同键覆盖 inferred,explicit 独有项追加**,顺序 = inferred 顺序在前、explicit 新增项在后。这就是"只写差异项":

```jsonc
// field_mappings 已声明 6 列;只有 phone→mobile_no 名字对不上
"columnMappings": [ {"from":"phone","to":"mobile_no"} ]   // 其余 5 列自动推断
```

---

## 5. 兼容性

- **现有全量显式配置零行为变化**:显式项覆盖同键推断项,结果集与今天逐列写完全一致(e2e seed `import-template-config-seed.sql` / `export-template-config-seed.sql` 应作为回归基线,断言 effective mapping 不变)。
- **唯一语义变化点**:今天"`columnMappings` 为空 → 报错"变为"为空 → 尝试推断"。无任何模板依赖"空必报错"作为契约,故无破坏性。
- `conflictColumns` 校验链不放松:`validateIdentifiers` 仍要求 `conflictColumns ⊆ effective 列集`(`JdbcMappedImportSpec.java:233-238`),严格幂等下仍强制非空(`:208-221`)。推断只扩 `effective` 列集,不削弱该承重墙。

---

## 6. 安全与校验(不退让的红线)

- 推断出的每个 `to`/列名**必须**过 `JdbcMappedSqlValidator.requireIdentifier` + schema 白名单(`allowed-schemas`,默认仅 `biz`),与显式项同一道闸,杜绝注入。
- 若仓库启用 `biz_table_schema` 列白名单(`V66`),推断列应额外受其约束(评审确认是否纳入本次)。
- fail-fast 文案要可定位:推断为空 / 规范化失败时,异常须指明"哪个 `field_mappings` 项、缺 `targetColumn`",对齐 quickstart §6 的"系统告诉你去哪填"。

---

## 7. 范围边界(防越界)

本设计**只**消除冗余声明、加推断兜底,属"文件交付闭环"的配置体验改进。**不做**:

- ❌ 不引入"读目标表 `information_schema` 反查列做自动建表/自动对齐"——那是数据治理,越界。
- ❌ 不改 LOAD/GENERATE 的执行语义(SQL 生成、UPSERT、cursor 分页全不动)。
- ❌ 不碰 `sql_template_export` 的 SQL 治理(JSqlParser/EXPLAIN)。
- ❌ 不新增模板表/字段以外的元数据来源。

---

## 8. 影响面与改动清单(实现阶段参考,本次不落地)

| 类型 | 位置 | 改动 |
|---|---|---|
| 代码 | `JdbcMappedImportSpec.parse` | 空 `columnMappings` 改为推断+合并;新增 `inferFromFieldMappings` / `normalizeColumn` |
| 代码 | `JdbcMappedExportSpec.parse` | `detail`/`batch` SelectColumns 推断兜底 + 合并 |
| 代码 | 共用 | `normalizeColumn` 工具(放 `batch-common` 还是各 worker?评审定) |
| 文档 | `worker-plugins.md` | 标注 `columnMappings`/`selectColumns` 可缺省 + 推断规则 |
| 文档 | `first-tenant-config-quickstart.md` §3 | 改写为"只写差异项"的精简样例 |
| 文档 | Excel 配置模板「字段说明」sheet | 把这两字段从必填★降级为"可选,缺省自动推断" |
| 测试 | 单测 | 推断、合并、`persist:false` 排除、规范化失败、白名单拦截 |
| 测试 | e2e seed | 加一份"省略 columnMappings"的精简模板跑通,断言与全量版结果一致 |

> 注:改 `batch-console-api` 控制层字段才需要同步 OpenAPI;本设计仅改 worker 解析语义,**不动控制层契约**,故不触发 `pr-gate` API 漂移闸(实现时复核)。

## 9. 待评审决策点

1. ~~`normalizeColumn` 的大小写/下划线兼容是否要做~~ — **已定(2026-06-20)**:作为默认能力做掉,含连续大写边界切分,大小写不敏感。用户只配置真正的语义差异。
2. `persist:false` 的字段开关命名 / 默认值是否可接受(默认全入库 vs 默认沿用 columnMappings 白名单语义)。
3. `biz_table_schema` 列白名单是否纳入本次推断校验。
4. `normalizeColumn` 工具落 `batch-common` 还是 import/export 各自实现(避免无谓共享耦合)。
