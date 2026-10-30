# 脚本目录、说明与 CI 索引治理 — 2026-10-24

> 本轮增量治理记录。长期规则在 [编码规约 §19.5](../coding-conventions.md#195-自动化脚本的头注释与说明)、[脚本任务路由](../../scripts/README.md)与[文档治理规范](../standards/document-governance.md)维护；文档目录前序整理见[同日文档分类记录](document-classification-and-naming-review-2026-10-24.md)。

## 1. 基线和边界

- 工作树：`feature/workflow-daily-integration`，HEAD `ee01e9751`，包含原有已暂存、未暂存及新增变更。本轮保留既有开发内容，没有提交、推送、创建 PR 或操作外部环境。
- 自动化脚本盘点为378份：Python160、Shell216、Git hook2。包含测试辅助脚本；SDK Python业务源码不按自动化脚本处理。原来没有固定且完整的用途/用法双字段，本轮已补齐并全量检查。
- 入口/公共模块308份、配套SQL480份、CI基线3份，共791份工具资产登记到最近的职责README。测试源经测试套件发现，不逐个混入操作入口索引。SQL只登记既有路径，不改正文；根 `db/migration` 的341份Flyway迁移未修改。
- 脚本一级职责目录从17个收敛到14个；保留数据库、发布、场景、高可用和对象存储策略的实际边界，不以统一命名或减少目录数为理由迁移所有文件。

## 2. 用户连续十项要求逐项跟踪

| 项目 | 本轮处理 | 权威入口 / 验证 |
|---|---|---|
| 1. 文档与脚本索引有MD登记并防漂移 | 文档守卫检查当前/归档逐层跳转及用途；脚本守卫从原来的一级README和守卫名称清单，扩展到真实资产跳转、用途、失效目标与父目录入口 | [文档规则](../standards/document-governance.md)、[脚本检查器](../../scripts/ci/check-script-governance.py) |
| 2. CI介绍覆盖全部流程 | 逐项核对23个workflow、3个composite action，登记触发条件、职责、结果和写入边界；纠正staging-gate被误写为前端外部staging验收 | [工作流总索引](../../.github/workflows/README.md)、[共享Actions](../../.github/actions/README.md)、[CI手册](../runbook/ci.md) |
| 3. 脚本头必须说明做什么与怎么用 | 固定用途/用法双字段，保持shebang、Python编码声明及future import合法位置；参数、依赖、产物和执行限制继续由正文说明 | [编码规约](../coding-conventions.md#195-自动化脚本的头注释与说明) |
| 4. Python也必须纳入 | Python工具写执行命令，公共模块写导入方式，测试辅助写测试命令；用AST检查文档字符串，不扫描普通协议字符串 | [脚本治理检查](../../scripts/ci/check-script-governance.py)及其自测 |
| 5. 内部说明性注释专业化，避免纯英文 | 校正73处纯英文Python文档字符串与说明性英文注释，补充/澄清隔离、执行顺序、失败传播与采集边界；保留命令、标识符、工具指令、许可证及代码示例 | 格式与语言检查通过；源码行为保持核对见下节 |
| 6. 优化整个脚本目录职责 | 一次性源码工具归dev，运行证据与升级结果只读检查归ops；明确ci/local/docker/deploy/ops/db/data/codegen/dev/ha/minio/sim/sim-4day/lib职责。minio保留配套策略与profile边界 | [脚本任务路由](../../scripts/README.md) |
| 7. 评估SQL是否统一提到根目录 | 不增加混合用途的根sql目录；迁移、操作SQL和共享场景输入分别维护，配套查询贴近调用方 | 本轮没有迁移SQL或改写SQL/Flyway正文 |
| 8. 明确scripts/db与根db的关系 | 保留根db/migration管理版本化结构演进，scripts/db管理巡检、备份、清理、业务库隔离和测试种子；本目录55份资产按用途导航 | [数据库操作说明](../../scripts/db/README.md)；当前约90个非SQL消费文件，不作无收益的整体搬迁 |
| 9. 所有迁移同步引用 | 同步脚本、守卫、构建/运行说明、README、Skill、测试与CI；不留下失效兼容目录 | 三个入口迁移和三个基线迁移如下；静态引用检查通过 |
| 10. 基线位置和后续更新 | 三份机器预算集中ci/baselines；SQL预算保持0，Java预算仅从265收紧至256（7个位置减少9处）；高风险预算不变。SQL读取器兼容目标分支旧路径，防止搬迁时绕过防增长比较 | [基线说明](../../scripts/ci/baselines/README.md)，零预算/缺失基准拒绝的回归测试通过 |

十项指本轮目录、说明、索引与基线治理要求，不代表系统功能验收或所有质量债务已经关闭。

## 3. 迁移结果与保留决定

| 原职责 / 资产 | 当前路径 | 依据 |
|---|---|---|
| tools中的源码维护工具 | [dev/inject_swallowed_exception_logs.py](../../scripts/dev/inject_swallowed_exception_logs.py) | 开发维护工具，执行后复核源码差异 |
| observability中的运行证据采集 | [ops/capture-runtime-evidence.sh](../../scripts/ops/capture-runtime-evidence.sh) | 只读运维采集；不启停服务 |
| staging中的升级结果检查 | [ops/verify-worker-rolling-upgrade.sh](../../scripts/ops/verify-worker-rolling-upgrade.sh) | 只读采集rollout/Pod/事件；不冒充业务恢复验收 |
| 内联SQL历史预算 | [baselines/sql-inline-baseline.tsv](../../scripts/ci/baselines/sql-inline-baseline.tsv) | 内容字节不变，当前预算0 |
| Java抑制预算 | [baselines/java-suppression-baseline.tsv](../../scripts/ci/baselines/java-suppression-baseline.tsv) | 按当前源码收紧9处，未增加条目或次数 |
| 高风险操作预算 | [baselines/destructive-ops-baseline.json](../../scripts/ci/baselines/destructive-ops-baseline.json) | 内容字节不变，不吞掉未审查命中 |

基线是工具配置，不属于验证报告、日志或测试数据。CI根目录的[Flyway Java工具](../../scripts/ci/FlywayMigrate.java)与[依赖扫描抑制配置](../../scripts/ci/dependency-check-suppressions.xml)保留为辅助资产；本轮不改写Flyway相关实现。

## 4. 守卫变更与验证

- 索引与脚本头检查沿用 `check-script-governance.py`，接入PR、Full CI和本地pre-commit，没有额外引入一套执行框架。workflow/action、职责目录及基线增删必须同步README。
- 共享范围分类器现在将deploy、SDK脚本目录和docs中的Shell入口也归入scripts，避免头注释规则只对scripts目录生效。守卫正反例、文档索引、日期策略、SQL基线路径与范围分类测试加入PR/Full/local现有测试组。
- 内部注释修改以Python AST去文档字符串、Shell去整行注释及批准的路径替换核对执行体；除明确修改的守卫、路由、基线读取与测试外，364份执行体一致；另2份在本轮期间发生了并发的config/quality配置迁移（仓库卫生守卫与PMD测试路径），已识别并保留，不归为本轮注释修改。本核对不是运行行为验收。
- 扩大自测发现Pipeline摘要检查将被Git忽略的tmp历史源码也扫入，导致执行器从4误计为19。先用隔离Git fixture复现，再改为当前Git可版本化源码清单；保留未跟踪生产源码覆盖，原有规则不降低。原20处命中排除副本后保留5处当前源码命中，待按语义判断。

| 实际命令 / 检查 | 结果 |
|---|---|
| 索引、头注释、日期、SQL基线及范围路由五个测试模块 | 最终61项全部通过 |
| `python3 scripts/ci/check-script-governance.py` | 通过：脚本头、语言、资产索引、83个守卫登记及直接执行权限 |
| `python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py` | 通过：当前/归档索引、命名日期和引用 |
| `python3 scripts/ci/check-schema-governance-assets.py` | 通过：数据库结构治理入口、SQL资产与说明 |
| `bash scripts/ci/check-shell-scripts.sh` | 216份Shell语法及ShellCheck通过；Git hooks另做语法检查 |
| `GUARD_BASE=origin/main python3 scripts/ci/check-sql-config-boundaries.py` | 通过：目标分支旧路径仍参与比较，历史内联SQL保持0 |
| `python3 scripts/ci/check-java-suppression-registry.py` | 通过：256处当前抑制均在已审查集合，未扩张 |
| `python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py'` 首轮 | 564项：561通过、2失败、1跳过；失败为Pipeline真实仓库契约与tmp副本导致的数量误计 |
| `python3 -m unittest scripts/ci/tests/test_check_pipeline_summary_keys.py` 修正扫描范围后 | 19项：18通过、1失败；执行器数量恢复4，仍保留5处当前源码键名混用命中 |
| `python3 -m unittest scripts/dev/tests/test_sonar_quality_gate.py scripts/dev/tests/test_sonar_incremental_report.py` | 21项通过 |
| `actionlint .github/workflows/pr-gate.yml .github/workflows/full-ci-gate.yml`、`git diff --check` | 通过 |
| 远端PR/Full CI、Java业务回归、完整SIM、真实数据库和staging | 本轮未运行；配置接入不等于目标SHA远端CI通过 |

最终再次执行全量脚本测试：566项，564通过、1失败、1跳过，耗时151.675秒。唯一失败仍是5处当前源码键名混用命中的真实仓库断言；1项因测试规范历史基线为空跳过，单项重新执行确认原因相同。未取消断言或扩张预算。日志位于本机 `/tmp/batch-script-ci-tests-final-20261024.log`，属于本地执行证据。

## 5. 未关闭项与交接

1. **SCRIPT-GATE-2026-10-24-01 / P2**：5处当前Java源码命中包括通知载荷、MyBatis参数名、SQL参数映射与共享运行键，不将全部命中认定为同一类业务缺陷。按命名空间及消费方复核；同一协议键应复用，有独立语义的SQL参数不能只因同值强行替换。保持真实仓库断言失败，不扩大白名单。
2. **SCRIPT-GATE-2026-10-24-02 / P2**：高风险操作守卫仍报告23个路径/类别相对历史预算增长；涉及已有迁移、清理SQL及本地资源操作。逐项评审目标与恢复边界，区分合法DDL、未审查破坏性操作及误报。本轮预算字节不变，未通过全量重生成将其放行。
3. 文档站此前的两份大记录仍超过发布预算，继续见 `DOCS-SIZE-2026-10-24`；本轮没有放宽预算或重跑完整站点构建。

当前问题入口同步[TODO Master](../plans/todo-master.md)。上述问题不因文件移动、静态检查通过或本地完成标签自动关闭。
