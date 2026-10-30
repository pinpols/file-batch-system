# 文档与脚本全量分析及技能优化

> 本文前部保留2026-10-25原审计范围；2026-10-26追加的界面元数据与条件参考整理见末节，不能据此继承旧全量清单对其他后续变更的验证。

## 目标、基线与授权

按用户要求分析全部文档和脚本，优化既有或补充职责缺失的技能；保留历史原文。修改范围仅`fix/wdi-current`工作树的技能、AGENTS、使用指南及必要审计/文档变更记录，不修改应用、执行脚本、Flyway、机器预算或配对前端。无需新分支或新工作树，不提交/推送，不编译、不运行应用测试、部署、迁移或故障演练。

后端：`fix/wdi-current` 工作树，HEAD `f3d5e400d`，含大量既有暂存/未暂存/未跟踪改动。配对前端仓库 `batch-console` 当时为 main/HEAD `662d254`，仅作只读参考，未修改。冻结的 Workflow 工作树及后端 main 不变。

## 覆盖分母与方法

范围取Git可版本化且实际存在的文件，不扫描target/node_modules、忽略的日志/产物或本机Secret。路径去重后共2157个来源：后端662份文本文档、1165份脚本/SQL/workflow、62份其他契约/数据和42份构建/调用声明；前端158份文档、64份脚本/workflow和4份构建声明。后端原始其他文档资产数63，其中一份可执行fixture验证脚本已计入script，避免重复。SQL迁移352份包含在1165中；它们是迁移集合，不伪装成352条独立经验。Makefile、Git hooks、Wrapper、Dockerfile与包/构建元数据单列，避免遗漏无扩展名的入口；未执行这些入口。另核对后端25个和前端12个技能的职责与路由，前端技能不在本轮修改范围。

对全部来源读取全文并计算SHA-256，提取标题、参数解析、共享调用、Python AST、主题与副作用候选；Python文本解析无语法错误，只是静态证据，不是运行检查。然后按现行规范、目录索引和真实入口/子调用交叉核对，66个重点来源标记人工交叉，其余如实标记全文静态盘点。大型执行日志、候选清单、CSV、SBOM和fixture按证据/数据资产处理，不能把关键词命中数或全文读取宣称为逐行人工业务审查。

[逐文件覆盖](document-script-skill-coverage-2026-10-25.csv)记录仓库、类型、原路径、哈希、行数、复核层级、技能归属及处理理由。副作用列只标记静态候选，不证明只读或危险；执行前仍读真实调用链。

## 实施决定与边界

- 新增`release-deployment`：联合版本、完整不可变release set、环境晋级与失败回滚。Git/PR、local开发和生产部署不混为一个授权。当前Compose CD的生产晋级尚未实现；技能要求现场核对workflow和环境，不能将设计目标或manifest状态字符串当门禁通过。
- 新增`observability-governance`：技术日志/业务审计、低基数指标、Trace、采集背压、告警持续/解除/同组活动事件与通知最终送达。只读现象诊断仍使用operations-analysis，不强制发送真实告警。
- 优化既有脚本、只读运维、开发、配置、前后端导入、数据库、灾备、验收、调度、SDK、对象存储、SQL、CI、Git交付及总控/文档技能；条件化细节写references，短入口按需加载，不复制整本手册。
- 不新增全量扫描器、门禁或扩大预算。已覆盖领域不为增加数量机械修改；原始资料和历史经验继续保留，不因提炼删除原文。

## 方法中的关键反例

1. `compose-release verify`会写本地覆盖层，`inspect-all`可跳过子项，整体退出0不等于所有检查执行；脚本名/参数名不能决定副作用和完成状态。
2. PostgreSQL Python fallback只支持部分psql行为，不把任意迁移/元命令交给它；URI authority与libpq host的IPv6表示不同。
3. Flyway注释或COMMENT ON的守卫放行不代表原库checksum兼容；空库切换、升级、真实恢复分开。
4. Dispatcher密钥的固定版本、轮换、撤销与已发出的未知请求分别处理；不能静默切最新版本或盲重发。
5. 技术计数器窗口resolved不证明具体业务故障修复，业务Alertmanager事件分组仍需租户边界；不能把Prometheus标签低基数规则错误套成业务事件禁止tenant。
6. 业务主表归档/按年龄清理不证明dedup或receipt可删，需证明最长重投/重放与外部副作用边界；旧手册的固定保留月数不固化为安全规则。
7. SDK源码fixture、构建包安装、registry发布和真实transport分层；配置包preview、apply、审批、发布、目标receipt及在途快照也分层。

## 实施状态与下一步

已完成17个既有技能的优化，新增2个技能，后端共27个。修改既有入口并新增按需方法参考页；配置方法归回配置技能，项目审查保留历史ID引用，避免重复维护规则。AGENTS、使用指南、审计索引及docs Changelog已同步。

| 实际命令或核对 | 结果 |
|---|---|
| `python3 "$CODEX_HOME/skills/.system/skill-creator/scripts/quick_validate.py" <skill-directory>` | 对后端27个技能分别运行，全部通过 |
| `python3 scripts/ci/check-docs-structure.py` | 通过 |
| `python3 scripts/ci/check-code-doc-references.py` | 通过 |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，dated=299、undated=281、current-dated-legacy=0 |
| `python3 scripts/ci/check-changelog-sync.py` | 通过 |
| `python3 scripts/ci/check-script-governance.py` | 通过，guards=83、direct-executable=4 |
| `git diff --check`、`git diff --cached --check` | 通过 |
| 技能本地链接、参考页可发现性及逐文件路由核对 | 本地链接均存在，参考页均有入口；2157条仓库/路径组合唯一，技能路由目录存在 |
| 初始来源哈希复核 | 后端脚本、SQL、构建入口和历史正文保持；仅AGENTS、审计索引、docs Changelog与技能指南4个既有资料入口按本轮计划更新 |

另按用户意图人工核对6类路由反例：只读Outbox诊断不转heal写入；普通PR不自动升版；只校验manifest不部署；SDK包验证不套平台联合版本；Prometheus低基数规则不删除业务Alertmanager的tenant隔离；Flyway注释检查放行不称原库checksum兼容。这是路由/边界审查，不是独立Agent行为测试，也不是目标环境验证。

配对前端在只读核对期间有14个来源文档同期变化或移除。覆盖CSV的收尾状态明确标记这些并行变更，保留最初读取的来源哈希；未写入、还原或阻挡前端变化，不把快照当作前端最新状态保证。后端不存在本轮范围外的来源哈希变化。

未编译、未运行应用/脚本测试、未执行部署/迁移/清理/压测/通知发送；静态读取和Python AST解析不证明所有脚本可运行，Skill格式检查也不证明真实任务行为或所有历史问题已验证。当前HEAD仍为f3d5e400d，未暂存、提交、推送或新建分支/工作树。

## 2026-10-26 Skill界面元数据与条件参考补充

用户追加授权整理前后端Skill元数据与按需参考。后端27份`agents/openai.yaml`逐字节保留；文档治理Skill只将历史证据保留／全量提炼的详细方法提取到`references/evidence-preservation-and-extraction.md`，原入口标题和DOC-1／DOC-2锚点保留，正文守恒。原有其他短Skill不机械拆分，已有参考维持职责与权威链接，不复制个人偏好或改变自动调用策略。

前端补齐原缺少的10份界面元数据；两仓39个现行Skill均有界面元数据。references按需存在于前端2个／后端23个Skill，共29个文件。具体改动、检查与本轮逐文件范围统一见[前端侧复核记录](../../../batch-console/docs/reports/2026-10-26-skill-metadata-and-references.md)，不把结构校验提升为应用验收或旧全量来源重新实跑。后端适用文档检查以本批实际结果为准。
