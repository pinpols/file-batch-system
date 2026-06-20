# 列映射配置体验:IMPORT 默认推断,EXPORT 列归 SELECT

> 日期:2026-06-20 · 状态:已实现(IMPORT 推断+1:1 基数校验、EXPORT detailSelectColumns 去重;PR #579)
> 范围:IMPORT `columnMappings` 默认推断 + 差异覆盖为主;EXPORT 维持列显式(SQL 投影),仅一处可选去重
> 关联:`docs/architecture/worker-plugins.md`、`docs/runbook/first-tenant-config-quickstart.md`、ADR 范围纪律(文件交付闭环,不扩张为数据治理)

## 0. 一句话

**IMPORT** 的 `columnMappings` 目前全量必填,而它要表达的列信息**在 `field_mappings` 里已经声明过一遍**——本设计让它缺省时从 `field_mappings` 自动推断,snake_case / 下划线 / 大小写的写法差异**默认归一化兼容**,显式配置只保留**真正名字对不上的语义差异项**。

**EXPORT** 不同:导出的列本质是 **SQL 投影**(`sql_template_export` 写在 SELECT 里、`jdbc_mapped_export` 写在 `detailSelectColumns` 里),归查询管、天然显式,**不做"默认列"**;仅在 jdbc_mapped 路径消除与 `field_mappings.sourceColumn` 的一处重复(可选去重)。

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

## 3. 设计目标(2026-06-20 收窄后)

1. **IMPORT 加列名自动映射兜底** — `columnMappings` 缺省时从 `field_mappings` 推断,显式项只写"名字对不上"的语义差异。这是本设计的**主战场**。
2. **写法差异默认归一化** — snake_case / 下划线 / 大小写差异默认兼容,不需用户配置(见 4.2)。
3. **EXPORT 不做"默认列"** — 导出的列本质是 **SQL 投影**,归查询管,不是该被默认掉的样板(见 §3.1)。仅在 `jdbc_mapped_export` 路径消除 `sourceColumn` 与 `detailSelectColumns` 的**一处重复声明**(定性为去重,非自动默认)。
4. **先出设计、不改码** — 本文档即交付物。

### 3.1 为什么 EXPORT 不该"默认列"(2026-06-20 用户澄清)

> "导出应该是 SELECT 语句里面自己支持的吧,这个没法默认吧?"

成立。导出有两条路径,列的归属都在查询侧:

| 导出路径 | 列从哪来 | 能否默认 |
|---|---|---|
| `sql_template_export`(写 `default_query_sql`) | **就在 SELECT 里**(`SELECT customer_no, customer_name ...`) | **不能也不该**。写 SELECT 本身就是列声明;`field_mappings` 这条路径只提供 `header`(表头),是独立信息不是冗余 |
| `jdbc_mapped_export`(不写 SQL,用 `detailSelectColumns`) | `detailSelectColumns` 即插件拼出的 SELECT 投影(`SELECT <detailSelectColumns> FROM detailTable`) | 本质仍是"写 SELECT",天然显式。唯一可收的是它与 `field_mappings[*].sourceColumn` 的**重复**——见 4.3,按去重处理 |

结论:EXPORT 列选择是**投影问题**,保持显式;不引入"默认导出列"。`batchSelectColumns` 等保持现状必填(它是 batch/detail 两表 join 的连接列,非用户可省的展示列)。

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

### 4.3 EXPORT:仅消除一处重复,不做默认列

按 §3.1,导出列保持显式。本设计对 EXPORT 只做一件**可选的去重**(非自动默认):

- `jdbc_mapped_export` 路径下,`detailSelectColumns` 缺省时可取 `field_mappings[*].sourceColumn`(去重、保序),避免用户把"导出哪些列"写两遍。这是**去重**,语义上等价于"投影列表的单一来源",不是"系统替你猜要导出什么"。
- `batchSelectColumns`、`batchNoColumn`、`detailFkColumn` 等**保持现状必填**——它们是 batch/detail 两表 join 的结构连接列,非用户可省的展示列,且 `batchSelectColumns` 必须含 `id`(`:83-85`)。
- `sql_template_export` 路径(`default_query_sql`)**完全不动**:列在 SELECT 里自决,`field_mappings` 只管 `header`。

> 取舍提示:若评审认为"`detailSelectColumns` 写一遍本就清晰、不值得引入隐式来源",可**只做 IMPORT、EXPORT 一处都不动**——EXPORT 的收益本就小,本节标记为可选。

### 4.4 合并语义(差异覆盖)统一定义

`merge(inferred, explicit)`:以 `from`(import)/列名(export)为键,**explicit 同键覆盖 inferred,explicit 独有项追加**,顺序 = inferred 顺序在前、explicit 新增项在后。这就是"只写差异项":

```jsonc
// field_mappings 已声明 6 列;只有 phone→mobile_no 名字对不上
"columnMappings": [ {"from":"phone","to":"mobile_no"} ]   // 其余 5 列自动推断
```

### 4.5 映射基数:强制 1:1,冲突 fail-fast(回答"一个名字映射两个列")

当前实现对两类"非 1:1"是**静默**的,本设计要把它们显式化:

| 形态 | 例 | 现状(`GenericJdbcMappedImportLoadPlugin`) | 本设计决策 |
|---|---|---|---|
| **A. 一个 `from` → 多个 `to`**(fan-out) | `{from:no,to:a},{from:no,to:b}` | `orderedInsertColumns:224` `to` 不同都加入,a/b 都填 `row[no]` → **能跑**(同值复写) | **默认 reject**。复写需求走 `systemBindings` 或 DB 侧,不进列映射;保持心智简单 |
| **B. 多个 `from` → 一个 `to`**(碰撞) | `{from:no,to:c},{from:legacy,to:c}` | `:224` 去重只留 `c`,`valueForColumn:253-256` 取**首个**匹配 → `row[no]`,`legacy` **被静默丢弃** | **必须 reject**(与推断无关的真 bug 修复):一列被两源争抢是语义二义 |

**统一不变量(对 merge 后的 effective 集)**:`from` 唯一 ∧ `to` 唯一(双向单射)。

- merge 的 override 会先把"同 `from` 的 inferred 与 explicit"塌缩成 1 条;塌缩后 `from` 仍出现 ≥2 次 ⇒ 用户写了真正的 fan-out ⇒ reject(形态 A)。
- `to` 出现 ≥2 次 ⇒ 碰撞 ⇒ reject(形态 B),报错指明是哪两个 `from` 撞了同一 `to`。
- 这把今天两处静默(A 默默复写 / B 默默丢列)都变成**显式失败 + 可定位文案**。

> 取舍:形态 A 的"默认 reject"是**策略选择可回退**——若将来确有大量复写需求,可改为显式开关放开;但默认从严,契合"只配置明确差异"。

### 4.6 对应靠"名字"不靠"位置"(回答"列数对齐?顺序可不一致?")

关键事实(`DelimitedFormatParser.java:70-94`):**带表头的格式按表头名建 `Map<header→value>`,LOAD 的 `columnMappings.from` 按名字取值(`row.get(m.from())`),与文件物理列序无关。**

| 问题 | 答案 |
|---|---|
| **默认是"列数跟文件列对齐"吗?** | **不是按文件物理列数/位置**。驱动映射的是 **`field_mappings` 声明的字段**,按**名字**对齐。文件多出的列 → 忽略;`field_mappings` 声明而文件没有的列 → 该值 null(required 由 VALIDATE 拦)。写入 DB 的列数 = 入库的 `field_mappings` 条数,不是文件列数。 |
| **顺序可以不一致吗?**(带表头:CSV 带 header / JSON / Excel / XML) | **可以**。按名匹配,文件列序随便排;DB INSERT 列序由 `columnMappings`/`field_mappings` 顺序决定(`orderedInsertColumns`),独立于文件。 |
| **无表头格式**(headerless CSV / FIXED_WIDTH) | **顺序与列数都必须对齐**——无表头只能**按位置**绑定,位置 schema 取 `field_mappings` 的声明顺序。**已修(2026-06-20)**:`DelimitedFormatParser` 无表头分支改用新 `ParseSupport.positionalHeaders()`——按 `field_mappings[*].name` 顺序绑定,仅当模板无 `field_mappings` 时才回退硬编码 `defaultHeaders()`(向后兼容)。FIXED_WIDTH 本就按 `field_mappings` 的 `target`/`start`/`length` 绑定,无此 gap。 |

> ⚠️ 另一个独立的名字匹配脆点(不在本次默认推断范围、但相关):`from`→文件表头是**精确字符串匹配**(`row.get(m.from())`),大小写/空格不一致会导致"整列变 null"(quickstart §6 已记此坑)。§4.2 的归一化目前只作用于 `name→DB列(to)` 侧;是否对 `from→表头` 侧也做大小写/trim 容错是更敏感的改动(动用户文件数据),列为待评审点而非默认纳入。

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
| 代码(可选) | `JdbcMappedExportSpec.parse` | 仅 `detailSelectColumns` 缺省时取 `field_mappings.sourceColumn` 去重;`batchSelectColumns` 等不动;sql_template 路径不动 |
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
5. **(§4.5)** fan-out(一 `from` → 多 `to`)默认 reject 是否接受(用户倾向不允许);碰撞(多 `from` → 一 `to`)改 reject 无异议。
6. ~~**(§4.6)** headerless CSV 的位置 schema 从硬编码 `defaultHeaders()` 改为 `field_mappings` 顺序~~ — **已修(2026-06-20)**:`ParseSupport.positionalHeaders()` + `DelimitedFormatParser` 无表头分支改造,模板无 `field_mappings` 才回退旧默认。
7. **(§4.6 脚注)** 是否对 `from`→文件表头匹配也加大小写/trim 容错(动用户文件数据,更敏感)。
