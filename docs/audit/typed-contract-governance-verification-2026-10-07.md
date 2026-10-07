# 固定契约与协议值治理本地验收记录

> 日期：2026-10-07
> 状态：本地实现与定向验证；未提交、未推送，CI / Full Gate / sim 未运行。
> 计划：[固定契约、有限域与常量治理计划](../plans/typed-contract-enum-constant-governance-plan-2026-10-07.md)。

## 范围与基线

后端、配对前端均在独立 `codex/typed-contract-governance` 工作树实现，未改动共享主检出目录中的既有内容。后端开发基线为 `88a96bd0a`，前端为 `bdeb7ee`；后续主线 `8cbdb811b` 的 Worker count 弃用 OpenAPI 注释已保留，避免重新生成类型时退回旧说明。

本轮只治理确认清单及高置信回退，不改业务状态机、配额、事务、锁、重试、发布或租户权限。

## 实施矩阵

| 对象 | 改造与保护 |
|---|---|
| Pipeline definition | Service 直接返回现有列表项 DTO；基础设施层转换 Mapper Map；非空结果验证 snake_case、分页和租户条件 |
| Import event | 固定 triggered/reason record；成功省略 reason，未触发保留原因，真实 JSON 测试 |
| Trigger / Console proxy | 固定状态、列表、动作 record；真实 RestClient JSON 转换和时间字段测试；租户作用域丢弃缺少 tenantId 的行，读降级/写失败传播不变 |
| Job trigger | sealed 联合替代 Object；普通响应保持字符串，dry-run 保持校验对象；MockMvc 覆盖两分支，OpenAPI 补录已有 wire 而非新增行为 |
| Lineage | typed 顶层和证据行，MyBatis 显式 constructor resultMap；保留热表/归档回退、排序、租户条件和空值规则；metadata_json 是动态对象 |
| 枚举 | instance/partition/workflow/outbox/tenant/notification/asset/file/import/export 复用既有 code；未将业务子集替换为全部枚举值 |
| 常量 | 复用已存在的 KEY/PARAM/MDC；另收敛 8 个类内稳定协议键；固定 API 路由常量不伪装成环境配置 |
| 前端 | 生成响应类型、Trigger 列表和运维消费点收敛；不再发送后端忽略的列表 tenantId query；无布局和文案行为改造 |

## 守卫与例外

`check-java-contract-governance.py`：

- JCON-1：Controller / Service 接口的固定返回值不得退回 Map/Object。
- JCON-2：只扫描 31 个已确认类与权威枚举 code；枚举修改会扩展复扫消费者。
- JCON-3：同类已有 KEY/PARAM/MDC 常量时，真实 key 消费点不得重新写字面量；注解、日志文案和值参数不参与。
- 8 个动态例外精确到路径、方法签名和返回类型，附业务理由；空违规基线，数量不准增长；通配例外、负数或布尔基线均拒绝。
- PR/local 增量；注册表/扫描器变化及 Full Gate 全量；注册表变更不再被视为纯文档。

Sonar S1075 对固定 Trigger API 路由的误报只允许该代理文件一次 suppression；新增文件或增加次数由 suppression 守卫拒绝。部署 host/port 仍来自内部客户端配置。该例外不允许硬编码部署地址。

## 已执行验证

| 层级 | 命令/证据 | 结果 |
|---|---|---|
| 扩展后端定向测试 | 受影响 Console、Orchestrator、Trigger、Import/Export/Dispatch/Atomic、Java SDK reactor（`-am`），末轮日志 `/tmp/bfs-typed-governance-tests-final.log` | 345 tests / 57 个报告类，0 failure/error/skip |
| 末轮契约补测 | PipelineDefinitionListContract、ImportEventArrivalController、LineageEvidenceResponseContract、TriggerProxyJsonContract 与 Console Trigger/Scheduler tests | 15 tests，0 failure/error/skip；包含与上行重复的定向复验，不累加为独立覆盖数 |
| 真实数据库/存储 IT | LineageEvidenceArchiveFallback、ImportIngressScanner、DispatchChannelHealthServiceIntegrationTest | 2 + 4 + 7 tests 通过；属于上述 345 tests |
| Java 构建质量 | 格式化后末轮 `./mvnw -DskipTests test-compile pmd:check spotless:check -fae -B` | 全 17 reactor 模块通过；未执行全量单测 |
| 守卫自测 | JCON、scope detector、suppression registry | 19 + 12 + 4 tests 通过 |
| JCON 增量与全量 | 相对 origin/main 与省略 base-ref 两种模式 | candidates=0，regressions=0 |
| OpenAPI | 路由一致性、breaking、前端 gen:api:check | 路由 400 一致，breaking 和生成漂移检查通过 |
| 前端 | typecheck、lint:check、job/triggers API 单测 | 24 tests、类型与 lint 通过 |
| 其他静态守卫 | Java logging、test conventions、Lombok/injection、readability、脚本治理、Shell、workflow lint | 已通过；Shell 按官方 warning 严重度门禁，不修改既有 info 提示 |
| 提交预检入口 | 使用独立临时 Git index 执行 `bash scripts/local/pre-commit-checks.sh` | 所有适用项通过；真实暂存区为空。测试约定守卫自测的 1 个历史基线用例按既有条件跳过，不计入后端 345 tests |
| 生成快照 | 独立 index 的 Lean LOC 与 Java 可读性清单生成及一致性检查 | 两者通过；包含新文件，不遗漏未跟踪的 DTO 和测试 |
| Sonar 首轮 | `reports/sonar/2026-10-07_14-52-03/` 增量报告 | 2 个 MINOR、0 hotspot；已修月份写法并按精确例外处理契约路由 |
| Sonar 末轮 | 独立项目 `file-batch-system-contract-governance-20261007`，`reports/sonar/2026-10-07_15-21-00/`，服务端 task `37daa7f7-24dc-4c9d-8ffa-d53556a79155` 完成 | 74 个 Java 变更文件的新增/修改行：0 OPEN issue、0 待审 hotspot；静态模式未采集覆盖率 |

测试运行日志和 Sonar 原始 CSV 在本机临时目录/忽略的 reports 中，不将日志、凭据或构建产物入库。

## 未声称完成的验证

- GitHub PR 检查、Full Gate、全量 Maven 测试、完整 sim、真实浏览器前后端操作和 staging 未运行。
- MockMvc/RestClient JSON 与 Testcontainers IT 是契约和投影证据，不等于真实长任务或生产高可用验收。
- 首轮全量 Sonar 的 OPEN S1192 候选为 236 个，末轮为 228 个；其中含文案、样例、SQL 和正常标签重复，不等于同数量的确认缺陷，不在本轮作为强制清零指标。末轮全量报告仍有 1076 个 OPEN issue，本轮只报告变更行增量为零，不声称全仓 Sonar 清零。新增代码的高置信同类协议键回退由 JCON-3 阻断，其他候选按原计划逐处人工判定。
- JCON 零候选仅指规则覆盖范围；它不证明所有嵌套 DTO、跨类常量或未登记有限域都不存在问题。

## 风险与交付约束

重点风险是序列化和 MyBatis 投影而不是调度状态机，已增加真实 Jackson/PG 保护。metadata 的驱动封装修正单独登记为 wire 规范化，不掩称全量 wire 完全相同。普通触发字符串与 dry-run 对象都保留，breaking gate 只对既有端点的该历史分支做精确澄清，不全局禁用类型/union 检查。

需要提交授权后按固定契约、enum/常量、守卫/文档分批提交；配对前端单独 PR。实际 CI 通过前不把计划标为 Implemented，不自动合并。
