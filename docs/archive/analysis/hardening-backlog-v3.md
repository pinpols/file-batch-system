# 🛡 硬化建议 Backlog · v3

> 产出日期：2026-04-20
> 基准：`docs/analysis/deep-issue-analysis-v3.md` 的 🛡 硬化建议清单（35 条）
> 用途：v4 治理启动的起点文档；首批 P1 列在末尾「v4 第一批候选」

本文档记录 v3 分析里没进本轮修复范围的硬化建议。每条标注：
- **真实范围**：有 ↔ 标记的是重复 / 已合并编号（避免重复做）
- **已闭环**：✅ 标记表示本轮修复已覆盖（包括 V62 附带收尾）
- **文件坐标**：便于快速定位
- **修复成本**：T-shirt 尺寸（XS <1h / S <4h / M <1d / L <3d）
- **优先级**：P1 有感知价值、P2 纵深改进、P3 低收益

---

## 一、安全硬化（7 条）

| 编号 | 问题 | 文件:行 | 成本 | P | 建议 |
|---|---|---|---|---|---|
| **S-1.5** | ChannelConfigMerge 黑名单不全（`enabled` / `receipt_policy` 等控制字段可被 user config_json 覆盖） | `ChannelConfigMerge.java:31-64` | S | **P1** | 黑名单改白名单：固定白名单 key 才允许 overlay |
| **S-1.6** | JsonUtils 未配 `FAIL_ON_UNKNOWN_PROPERTIES` | `JsonUtils.java:13` | XS | P2 | 静态 ObjectMapper 加 `.configure(FAIL_ON_UNKNOWN_PROPERTIES, true)`；监控字段演进不破坏旧调用 |
| **S-1.7** | SMTP 通道攻击面未审：附件大小 / TLS 强制 / From 伪造 / CRLF header 注入 | `SmtpEmailDispatchChannelAdapter.java` 全文件 | M | **P1** | 一次专项审查：`mail.smtp.ssl.enable=true` / `mail.mime.splitlongparameters=false`；attachment size cap；from 白名单；header sanitize |
| **S-1.8** | EncodingUtils 不剥离 UTF-8 BOM（工具未暴露；PreprocessStep 自己处理） | `EncodingUtils.java:35-44` | XS | P3 | 加 `stripBom(InputStream)` 工具方法，`PreprocessStep.resolveCharset` 复用 |
| **S-1.9** | Guard.require 错误码硬编码 `INVALID_ARGUMENT` / `NOT_FOUND` | `Guard.java:52-56` | XS | P3 | 加重载 `require(boolean, ResultCode, String)`；业务需要 CONFLICT 时无需绕过 |
| **S-1.10** | SqlTemplateExportSqlValidator 参数校验不区分模板/业务（未提供参数替换为 null） | `SqlTemplateExportSqlValidator.java:156-168` | S | P2 | 声明必填 + 可选两列；必填缺失立即抛 INVALID_ARGUMENT；可选显式默认 |
| **S-1.11** | BatchSecurityProperties 注释与实际配置不符（IDE local 默认值标错） | `BatchSecurityProperties.java:16-23` | XS | P3 | 直接改注释。记一下 CLAUDE.md §21 的正确默认值 |

## 二、并发与一致性硬化（4 条）

| 编号 | 问题 | 文件:行 | 成本 | P | 建议 |
|---|---|---|---|---|---|
| **C-2.8** | QuotaRuntimeStateService 窗口过期重置竞态（两并发请求各算新窗口 → CAS 冲突重试 → 配额短暂失效） | `QuotaRuntimeStateService.java:216-281` | M | **P1** | Redis Lua 脚本原子"读 + 判断过期 + 重置"；或 select-for-update 行锁；V62 version 列已备 |
| **C-2.13** ↔ | LaunchBatchDayService 时区 fallback `ZoneId.systemDefault()` | `LaunchBatchDayService.java:174-180` | ✅ | — | **已关闭** · V62 `timezone_snapshot` 字段 + `BatchTimezoneProvider.resolveOrDefault()` 覆盖此路径 |
| **C-2.14** ↔ R-4.12 | HttpOrchestratorTriggerAdapter 无 secret 自我校验 → 配置漂移只能靠 401 发现 | `HttpOrchestratorTriggerAdapter.java:18-31` | S | P2 | startup 期调一次 orchestrator `/internal/ping` 用当前 secret 预检；不通则 fail-fast + 显式告警 |
| **C-2.15** | ParseStep 跳过阈值判定不区分 parse 错 vs validation 错 | `ParseStep.java:50-134` | S | P2 | 区分 skip counter；两类阈值分别配置；`support.withinThreshold(parseErrors, validationErrors)` 分参 |

## 三、架构与设计硬化（13 条）

| 编号 | 问题 | 文件:行 | 成本 | P | 建议 |
|---|---|---|---|---|---|
| **A-3.5** ↔ C-2.7 | LoadStep §9.9 幂等：业务主键 + batch_id UNIQUE | `LoadStep.java:78-155` | L | **P1** | **模板层审查**：每张目标表都定 conflict_columns；可出守护测试扫未定义的模板。C-2.7 b 已加 idempotency=OFF 日志 |
| **A-3.7** | `DefaultConsoleTenantConfigInitApplicationService.insertJobDefinition` 20+ 行 setter，多参数方法 >6 | `DefaultConsoleTenantConfigInitApplicationService.java:315-376` | M | P2 | 抽 `JobDefinitionInsertCommand` record，builder 或 Lombok `@Builder`；字段新增编译期可查 |
| **A-3.8** | JobTaskQuery 5 字段但测试大量 `null, null, null` 传参 | `JobTaskQuery.java:5-10` | XS | P2 | 加 `ofJobInstance(tenant, instanceId, page)` 工厂；ArchUnit 规则扫 `new JobTaskQuery(...null...)` |
| **A-3.9** | DispatchChannelHealthService 缺 half-open 探针 | `DispatchChannelHealthService.java:67-80` | M | **P1** | 按 circuit breaker 三态：CLOSED / OPEN / HALF_OPEN；OPEN 进入后到 probeInterval 放一次试探请求 |
| **A-3.10** ↔ | ValidateStep 删输出文件时序倒置 | `ValidateStep.java:77-102` | ✅ | — | **已关闭** · R-4.3 本轮修复 |
| **A-3.11** ↔ S-1.5 | ChannelConfigMerge 黑名单设计不完整 | 同 S-1.5 | — | — | 跟 S-1.5 合并做 |
| **A-3.12** | AbstractExportFormat 列数无上限 | `AbstractExportFormat.java:30-90` | XS | P2 | 加 `maxColumns`（默认 1024）配置；超限抛 INVALID_ARGUMENT |
| **A-3.13** | CHARSET_TRANSCODE 输出无大小限制（GBK→UTF-8 膨胀可跳过 ReceiveStep OOM 守卫） | `ImportPreprocessPipeline.java:62-114` | S | P2 | 加 outputSizeCap = inputSize × 1.5 + 1MB 硬上限；超限 abort |
| **A-3.14** | ImportIngressScanner 多实例竞态（文件可能被重复处理） | `ImportIngressScanner.java` 全文件 | M | P2 | 分布式锁：文件指纹（name + size + mtime）为 Redis key，setnx 拿锁后处理 |
| **A-3.15** ↔ S-1.11 | BatchSecurityProperties 注释与配置不符 | 同 S-1.11 | — | — | — |
| **A-3.16** | Dashboard 聚合 TTL 10s + 多表 join，更新窗口内可能读到半更新视图 | `ConsoleQueryCacheService` 多处 | L | P3 | 改用 PostgreSQL `REPEATABLE READ` 隔离 + 快照 timestamp，或引物化视图定时刷新 |
| **A-3.17** | PlatformFileRuntimeRepository 字面量常量只在类内修（没抽全局） | `PlatformFileRuntimeRepository.java:47-54` | XS | P3 | 抽到 `PipelineRuntimeKeys` 或独立 `FileStorageColumns` 常量类 |
| **A-3.18** | ExportFormat 生成端 / checksum 端 charset 可能不一致 | `GenerateStep.java:79-100` | S | P2 | 生成与 store 共同使用 `ExportContext.charset` 传递；StoreStep 读 context 而非模板 |

## 四、运维与可靠性硬化（10 条）

| 编号 | 问题 | 文件:行 | 成本 | P | 建议 |
|---|---|---|---|---|---|
| **R-4.7** | ConsoleSessionRegistry Caffeine 100k 无驱逐策略 | `ConsoleSessionRegistry.java:60-64` | XS | P2 | 显式 `.expireAfterWrite` + `.maximumSize` + `.recordStats()`；暴露 hit rate 到 micrometer |
| **R-4.8** | ConsoleQueryCacheService 失效靠 prefix evict + 手工调用 | `ConsoleQueryCacheService.java` | M | **P1** | AOP `@CacheEvict` 在所有 write Mapper 上统一拦截；守护测试扫未加的 write 方法 |
| **R-4.9** | Excel SAX 仍把所有 rows 缓存内存（百万级 OOM） | `ConsoleSingleSheetExcelImportSupport.java:202-280` | M | P2 | 改 row callback 模式，写到 NDJSON 临时文件；导入流程已有 preprocess spool 可参考 |
| **R-4.10** | ChannelConfigMerge 凭证 merge 可能被日志打出 | `ChannelConfigMerge.java` | S | **P1** | 敏感 key 黑名单（`password` / `secret` / `token` / `private_key`）脱敏到 `****`；log interceptor |
| **R-4.11** ↔ S-1.10 | SqlTemplateExportSqlValidator 参数校验 | 同 S-1.10 | — | — | — |
| **R-4.12** ↔ C-2.14 | Trigger → orchestrator secret 漂移感知 | 同 C-2.14 | — | — | — |
| **R-4.13** ↔ R-4.4 | Lease renewal 网络抖动无退避重试 | `WorkerTaskLeaseRenewer.java:26-52` | ✅部分 | — | 本轮 R-4.4 a 已加连续失败计数 + error log；真退避重试留 P3 |
| **R-4.14** ↔ S-1.6 | JsonUtils SPI 扩展点缺失 | 同 S-1.6 | — | — | 与 S-1.6 合并做（static + builder 两套 API） |
| **R-4.15** | MinioBucketSupport CAS 微优化 | `MinioBucketSupport.java:41-45` | XS | P3 | 当前无缺陷；有空再优化 |
| **R-4.16** | ConsoleTextSanitizer null-safe 链式 API | `ConsoleTextSanitizer.java` | XS | P3 | 返回自身支持 `.stripHtml().normalize().trim()`；纯 DX 提升 |

---

## 统计

| 维度 | 独立条目 | ↔ 重复（已合并） | ✅ 已闭环 | P1 | P2 | P3 |
|---|---|---|---|---|---|---|
| 安全 | 7 | 0 | 0 | 2 | 2 | 3 |
| 并发一致性 | 4 | 1 (R-4.12 指向 C-2.14) | 1 (C-2.13) | 1 | 2 | 0 |
| 架构 | 13 | 2 (S-1.5/A-3.11, S-1.11/A-3.15) | 1 (A-3.10) | 2 | 6 | 2 |
| 运维 | 10 | 3 (S-1.10/R-4.11, C-2.14/R-4.12, S-1.6/R-4.14) | 1 (R-4.13 部分) | 2 | 2 | 3 |
| **合计** | **34** | **6** | **3** | **7** | **12** | **8** |

> 去掉 6 条重复 + 3 条已闭环 = **真实独立待做 25 条**
> 其中 P1 = 7 条（建议列入 v4 首批）

---

## v4 第一批治理候选（7 条 P1）

按影响面 × 修复成本排序：

| # | 编号 | 事项 | 影响面 | 成本 |
|---|---|---|---|---|
| 1 | **A-3.9** | DispatchChannelHealthService half-open 探针 | 故障渠道自动回流，避免人工重置 | M |
| 2 | **R-4.8** | 缓存失效 AOP 化 | 一次性堵所有 cache staleness 漏洞 | M |
| 3 | **C-2.8** | QuotaRuntimeStateService 窗口过期原子重置 | 高并发场景配额准确性 | M |
| 4 | **R-4.10** | ChannelConfigMerge 凭证日志脱敏 | 日志平台 / 转发链防凭证泄漏 | S |
| 5 | **S-1.7** | SMTP 通道专项审查 | 当前攻击面不清晰 | M |
| 6 | **A-3.5** | LoadStep §9.9 幂等补齐（模板层） | 重跑安全性 | L |
| 7 | **S-1.5** | ChannelConfigMerge 黑名单→白名单（和 R-4.10 同文件，一起做） | 渠道策略不被 user overlay 绕过 | S |

**合计工作量**：~1.5 人周

## v4 第二批候选（12 条 P2）

按成本升序：
- **XS / S**（< 4h 各）：S-1.6、S-1.8、S-1.9、S-1.10、A-3.8、A-3.12、A-3.13、A-3.18、R-4.7
- **M / L**（> 1d 各）：A-3.7、A-3.14、R-4.9

## v4 第三批候选（8 条 P3）

不紧迫的纯质量 / DX 提升，可在空闲迭代见缝插针：
S-1.11（= A-3.15）、A-3.16、A-3.17、R-4.15、R-4.16

---

## 和 v3 的关系

- v3 bug 清单 15 条已在 2026-04-20 闭环（见 `fix-report-v3.md`）
- v3 🎯 设计意图 10 条按 Item 5 决策实施（见 `fix-report-v3.md` §二）
- 本 backlog = v3 🛡 硬化建议 35 条的结构化台账，作为 v4 治理的单一入口

## 使用建议

1. 每条启动前先 `grep` 关键文件确认问题仍在（代码可能已被其他 PR 顺手修掉）
2. 去重优先：跨维度 ↔ 编号合并做，减少 PR 数
3. 用 micrometer 先做"监控"再做"行为"——P1 的 R-4.8（缓存失效）和 R-4.10（凭证日志）先上指标，再上修复，便于事后回归验证

---

*生成日期：2026-04-20*
*下一次更新触发：v4 治理启动后按条目闭环状态回填 ✅ 标记*
