# 测试编码约定审查（2026-10-25）

## 结论与范围

**现有测试没有全部遵守现行编码约定。** 中文展示名/命名门禁、共享测试配置和flaky隔离检查通过，但SQL承接、JDBC断言、Mock初始化、AssertJ和LENIENT理由仍有缺口。命名门禁通过只证明工具当前判定范围，没有证明全部§14均合规。

本轮为审查与留档，没有批量修改测试源码或扩大机器基线。范围是最新集成开发工作树 `fix/wdi-current`：后端 HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`；配对前端 `batch-console` HEAD `662d254e2f8b97f93021de548815470d2291c997`。包含暂存、未暂存及未忽略的未跟踪源码，排除已删文件、生成物和忽略的历史备份；没有以冻结 main 或旧 feature 工作树代替。

使用 `backend-testing-governance`、`code-review-and-gates`、`frontend-testing-ci` 与 `documentation-governance`。后端规范以[编码约定§14](../coding-conventions.md#14-测试规范)为准；前端使用配对仓库 `docs/testing/README.md` §9。后端枚举 **1392个Java测试源码文件**，定位 **1337个含测试方法的类、7759个测试方法声明**；参数化/动态测试未展开运行。前端枚举 **297个用例文件（149个Vitest、148个Playwright）**，定位1600个静态标题；既有配置事实来源守护另外覆盖315个测试及支持文件。数字是本轮源码快照，不作长期固定数量。

## 已确认的规范问题（P3）

| 条款 / 问题 | 当前量级 | 证据与影响 |
|---|---|---|
| §14.8 SQL先具名承接 | **21处、3文件**：19处直接字面量/拼接，2处直接读取SQL文件 | 新增的ReadinessRecoveryReproTest有16处、TriggerLaunchRecoveryMapperIntegrationTest有3处；DispatchChannelHealthServiceIntegrationTest另有2处Files.readString。SQL、绑定参数和断言混在调用处，阅读及后续维护成本高 |
| §14.8 JDBC结果先承接再断言 | **26处、12文件** | 含assertThat入参、isEqualTo期望值及as诊断参数中的SQL调用；应先保存准确类型的查询结果或受影响行数，再断言 |
| §14.2 声明式Mock初始化 | **11个文件仍使用openMocks**；字段直mock另有**252处/105文件候选** | openMocks位置已核对；字段候选经类级深度过滤、排除方法内mock，但未逐个裁定辅助类/fixture所有权，不能把252全部称为已验证业务缺陷 |
| §14.6 AssertJ | **8个非legacy文件仍有普通JUnit断言** | 人工确认约30处assertEquals/assertTrue/assertNotNull/assertThrows/assertDoesNotThrow调用；另2个security-scan文件属于明确存量豁免，1个抢占式超时文件单列，不机械替换其执行语义 |
| §14.3 LENIENT理由 | 17个使用文件中，**5个没有说明豁免理由** | DefaultScheduleForwarderSetBasedTest、DefaultWorkflowNodeDispatchServiceAllTypesTest、OutboxPublishCircuitBreakerTest、KafkaTriggerEventPublisherTest、AbstractWorkerLoopTest。其余有理由文字，不据此宣称全部语义必要；ConsoleTenantReadinessServiceTest说明在行尾，未按“注解上方”排版 |
| 前端§9标题 | **2处** | useOnboardingTour.test.ts以shouldShowOnboarding开头；应以首次访问/已完成等行为描述替代函数名前缀 |

完整位置、分类和候选/例外状态见[逐项CSV](test-code-conventions-findings-2026-10-25.csv)。类别可能重叠，不能相加为“违规测试总数”，也没有据此计算全仓合规百分比。

## 本轮重点：SQL与调用分开

SQL组织有两层：可复用运维/共享初始化脚本放在已有SQL资产目录；测试场景专属SQL可在本方法内用语义明确的局部变量和text block表达。不是所有测试SQL都应新增文件，更不能为消除字面量引入万能SQL工厂。

当前直接SQL的可复核入口：

- [TriggerLaunchRecoveryMapperIntegrationTest.java:50](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/TriggerLaunchRecoveryMapperIntegrationTest.java#L50)：cleanup直接传delete SQL；76、80行仍拼接更新SQL，共3处。该类相对main为新增文件。
- [ReadinessRecoveryReproTest.java:77](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ReadinessRecoveryReproTest.java#L77)：准备、清理、恢复后回查共16处直接SQL，含text block直接入参和字符串拼接；该类相对main为新增文件。
- [DispatchChannelHealthServiceIntegrationTest.java:235](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchChannelHealthServiceIntegrationTest.java#L235)：SQL已外置，235、236行仍在execute入参直接读文件。按现行§14.8先具名承接；如保持两次执行/读取，提取时保留各自时机，不顺便缓存或合并。

期望写法：

```java
String deleteRecoveryRowsSql = """
    delete from batch.trigger_launch_recovery where tenant_id = ?
    """;
int deletedRecoveryRows = jdbcTemplate.update(deleteRecoveryRowsSql, tenantId);
assertThat(deletedRecoveryRows).isZero(); // 仅在该场景验收条件确实要求零行时使用
```

示例说明结构，不给现有cleanup新增零行断言。重构必须保持SQL运行值、租户条件、绑定顺序、返回类型、异常/事务时机和测试自身数据所有权。

JDBC嵌套断言实例：[PipelineProgressArchiveIntegrationTest.java:412](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/PipelineProgressArchiveIntegrationTest.java#L412)、[RlsScriptPartitionIntegrationTest.java:250](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/rls/RlsScriptPartitionIntegrationTest.java#L250)。轮询查询应留在原untilAsserted回调，事务查询留在原事务，异常用例的触发动作也留在原异常回调；不因提取变量而预先执行。

## 其余检查与需要区分的情况

- 模板/命名门禁报告0缺口，当前基线也为0；不再引用规范中的旧存量2308条数字作为当前事实。守护实现仅接受should大写前缀或下划线，未验证完整“条件/预期”语义，例如`shouldRecordSuccessTimerForGet`仍会通过，因此不能据此保证全部方法名满足文字规范的完整形状。
- `@Testcontainers`与`@SpringBootTest`同时标注的组合候选为0；并未逐个验证所有集成测试继承链或容器生命周期。
- 前端定位到**71处固定waitForTimeout**，属于可靠性候选，需要结合动画/去抖/显式等待目的逐项判断；不把71条全部算为确认违规。另有15个vi.mock文件未在本文件找到mockReset/resetAllMocks，部分使用mockClear/clearAllMocks，需核对实现是否稳定与隔离要求，不自动认定状态泄漏。
- SQL定位器共识别6070个JDBC候选调用位置；非简单首参中排除5个Mockito anyString匹配器、1个专用连接回调，另2个读取外置SQL文件的调用单独记录。没有把DSL匹配器或会话/锁探针改判为SQL缺失。
- 旧[2026-10-24测试SQL整改记录](test-sql-and-assertion-governance-2026-10-24.md)适用于当时feature工作树与1376文件快照，不证明本轮wdi-current已归零。当前两份新增测试等变化须按最新树重新检查。

## 实际命令与验证边界

| 命令 / 方法 | 结果 |
|---|---|
| git worktree list、branch、rev-parse、porcelain快照 | 后端/前端fix/wdi-current已核对，保留原改动 |
| python3 scripts/ci/check-test-conventions.py --report | 全量五类展示名/方法名候选0；report模式本身恒退出0，结论来自实际内容 |
| python3 scripts/ci/check-test-conventions.py --check-baseline docs/governance/test-conventions-baseline.txt | 退出0，基线0、当前0、新增0 |
| python3 scripts/ci/check-test-config-sources.py | 退出0 |
| python3 scripts/ci/check-flaky-test-governance.py | 退出0 |
| python3 -m unittest scripts/ci/tests/test_check_test_conventions.py | 30项中29通过、1跳过、0失败；空基线使“基线文件存在性”用例跳过，提示文案为“基线尚未生成”，不把该跳过算通过 |
| 前端npm run check:test-fixture-sources | 初次退出1：当前工作树未安装typescript，不是代码违规 |
| node --loader /private/tmp/bfs-test-review-ts-loader.mjs scripts/check-test-fixture-sources.mjs | 退出0、内置正反例及315文件检查通过；临时只读loader使用已存在的同版TypeScript 6.0.3，没有安装依赖或修改前端源码，不能写成标准npm入口已正常通过 |
| 临时Java词法定位器与前端TypeScript AST定位器 | 形成上述源码/候选清单；Java部分不是编译器语义检查，不能保证没有动态/别名调用遗漏 |

未执行产品Java编译、单元/集成/E2E、前端typecheck/lint或浏览器业务验证；本轮29项是Python守护自测，不是产品业务测试。没有从“编码候选”推导生产故障、测试有效性或PR全门禁通过；测试所断言的业务闭环、事务真实性、共享资源释放和所有独立预期仍需专项审查。

后续按[当前TODO](../plans/todo-master.md#测试编码约定核查2026-10-25)先处理用户明确关注的SQL承接/JDBC断言，再按模块迁移Mock和普通JUnit断言，按语义保留超时与故障探针。所有源码整改保持与审查留档分开，不修改基线掩盖问题。
