# ADR-040 · 清单驱动的动态到达组 —— 上游批次清单声明当天预期文件

- **Status**: Proposed
- **Date**: 2026-06-20
- **Related**: PR #570（入站 sidecar manifest 强校验 / PR1）、PR #571（到达组 verified 触发 / PR2）、`docs/design/file-integrity-sidecar-manifest-design-2026-06-07.md`、`docs/design/file-pipeline-design.md` §完成标记 / §到达组
- **Plan**: 见本 ADR §实施分阶段。本文档先定协议 + 时序 + 改动点,**不含实现**;评审通过后分阶段落地。

## 范围边界

「**上游用批次清单文件动态声明「当天这一批包含哪些文件」,平台据此动态成组判定到齐**」√

- ✅ 上游每个业务日产一个**批次清单对象**(JSON),列出当天该组的预期文件名集合
- ✅ 平台读批次清单 → 动态推导该 `(tenant, fileGroupCode, bizDate)` 的 `requiredFileSet`,替代静态配置
- ✅ 复用既有到达组满足条件判定(`requiredFileSet` 全到 + ADR 无关的 PR2 verified 闸)与超时回退(`timeout-action`)
- ✅ 解决「地区每天不固定 + 文件名带时间戳」导致静态 `requiredFileSet` 配不出的缺口

「不做的部分」(留 follow-up / 明确不做):

- ❌ **不做** zip/tar 包内多文件展开成多 run(见对话结论 + 与 run 模型冲突,需独立 ADR;本系统压缩包仍是「单数据文件的压缩包装」)
- ❌ **不做**异构文件的「子文件→模板路由」;每个成员仍各自挂自己的 `templateCode`(到达组本就支持成员异构、导不同表)
- ❌ **不做** workflow/DAG 编排;本 ADR 只管「文件到达批次构成」,不扩张为任务编排
- ❌ **不替代** per-file `.chk`(PR1):批次清单声明「**有哪些文件**」,per-file manifest 声明「**每个文件对不对**」,两层正交
- ❌ **不做**跨业务日的批次合并 / 滚动窗口;一个批次清单 = 一个 `(group, bizDate)`

## 背景

到达组(arrival group)当前靠**静态 `requiredFileSet`**(逗号分隔精确文件名清单)判定到齐(`FileGovernanceScheduler.evaluateArrivalGroup`)。真实场景打破了这个前提:

1. **地区每天不固定**:今天 5 个区、明天 8 个区,预期文件集合是动态的。
2. **文件名带时间戳**:`region_BJ_20260620.csv` 每天变(bizDate 维度由 scanner `bizDatePattern` 已能抽取,但**文件清单本身**无法预先列死)。

静态清单对此无解。需要让**上游在批次层声明当天构成**——这是批量交付的标准做法(control file / batch manifest)。

## 决策

### 1. 批次清单协议(`batch-manifest-v1`)

**对象命名**(scanner 按后缀识别,后缀可配,默认 `.batch.json`):

```
<fileGroupCode>_<bizDate>.batch.json
例:region-daily_20260620.batch.json
```

**内容**(JSON):

```json
{
  "schemaVersion": "batch-manifest-v1",
  "fileGroupCode": "region-daily",
  "bizDate": "2026-06-20",
  "tenantId": "t1",
  "requiredFiles": [
    "region_BJ_20260620.csv",
    "region_SH_20260620.csv",
    "region_GZ_20260620.csv"
  ],
  "generatedAt": "2026-06-20T08:00:00+08:00"
}
```

- `requiredFiles`:当天该组预期的**完整文件名**集合(动态,每天不同)。
- 批次清单自身可选带 per-file `.chk`(`region-daily_20260620.batch.json.chk`)做完整性背书,复用 PR1 的 MANIFEST 校验;清单是 KB 级小文件,亦可仅靠稳定窗口。
- 由**上游**(或某个协调方 / 主上游)产并随当天数据一起上传。

### 2. 时序与回填(核心难点)

scanner 每轮扫描会看到对象集合。批次清单与数据文件的到达顺序不定,两种都要支持:

- **清单先到 / 同批到**:scanner 登记某数据文件时,查到同 `(group, bizDate)` 的批次清单 → 把 `requiredFiles` 写进该文件 `file_record.required_file_set`(metadata)。到达组判定即可算 `missingFiles`。
- **数据文件先到、清单后到**:数据文件登记时还没清单 → `required_file_set` 暂空 → 到达组「空清单守卫」跳过(现状)。清单后到时,scanner 须**回填**:对该 `(group, bizDate)` 下**已登记**的成员 `file_record` 批量补写 `required_file_set`。

**回填的承载**:把批次清单持久化为一条 `file_record`(`file_category=CONTROL` 或新增轻量状态),scanner 每轮对「已知批次清单 × 其 group/bizDate 下已登记成员」做对账补写(幂等:`required_file_set` 已一致则跳过,复用 `updateGroupState` 的 idempotent-skip 思路)。

### 3. 判定与触发(复用,不改)

`requiredFileSet` 一旦由清单注入到成员 `file_record`,**到达组判定逻辑完全不变**:

- `evaluateArrivalGroup` 读 `required_file_set` → 算 `missingFiles` → 全到 + `triggerOnComplete` → `TRIGGERED`。
- 清单声明了但一直没到的文件 → 现有 `latestTolerableTime` + `timeout-action`(`MANUAL_CONFIRM`/`BLOCK`/override)回退,不会无限等。
- **PR2 verified 闸正交叠加**:`require-verified=true` 时还要求每个成员有 per-file manifest 背书才放行。

### 4. 三层完整性的正交关系(全景)

| 层 | 回答什么 | 谁产 | 机制 |
|---|---|---|---|
| per-file `.chk`(PR1) | 每个文件对不对(size/checksum/recordCount) | 该文件上游 | 入站 MANIFEST 校验 |
| verified 闸(PR2) | 到达组放行前是否都已背书 | 平台判定 | `require-verified` |
| **批次清单(本 ADR)** | 当天这批**有哪些文件** | 上游/协调方 | `batch-manifest-v1` 动态成组 |

## 改动点

- **batch-worker-import / `ImportIngressScanner`**:① 按后缀识别批次清单对象并解析(新 `BatchManifest` record);② 登记数据文件时注入 `required_file_set`;③ 清单后到的回填对账。配置:`scanner.batch-manifest.enabled` / `.suffix`。
- **batch-orchestrator / `FileGovernanceScheduler`**:判定逻辑**不变**(已读 `required_file_set`)。仅可能加可观测(批次清单声明数 vs 已到数指标)。
- **DB**:不新增热表;批次清单走 `file_record`(CONTROL 类别),`required_file_set` 已在 metadata。**不触发 archive 冷表 schema 漂移**。
- **docs**:`file-pipeline-design.md` §到达组补「清单驱动」小节。

## 实施分阶段

- **Phase 1**:批次清单识别 + 解析 + 「清单先到/同批到」时登记注入 `required_file_set`。覆盖「上游先放清单再放数据」的常规交付顺序。
- **Phase 2**:「数据先到、清单后到」的回填对账(scanner 每轮幂等补写已登记成员)。覆盖乱序到达。
- **Phase 3**:批次清单自身 `.chk` 完整性 + Console 可视化(声明数/已到数/缺失清单),与 PR2 verified 闸联动展示。

## Consequences

- **正面**:动态地区 + 时间戳场景无需人工维护静态清单;成员仍可异构、各挂模板、导不同表;完全复用既有到达组满足条件 + 超时 + verified,改动集中在 scanner 一侧。
- **代价**:依赖上游产批次清单(产不出则退回「不等齐、各到各导」或静态清单);回填对账带来每轮少量额外查询(受 batch-size 限制)。
- **风险**:批次清单与 per-file `.chk` 两套协议需文档清晰区分,避免上游混淆「批次清单」与「单文件 manifest」。
