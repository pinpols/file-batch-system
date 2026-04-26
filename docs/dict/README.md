# 字典索引（dict/）

**Reference 类文档**（按 Diátaxis 框架）。每个文件是单一类目的权威值表，**不解释为什么**，只列"是什么 / 在哪里"。

> 设计原则：dict 文件是**自动生成**或**自动校验**的，**不允许手工编辑**。改源（Java 枚举 / `@ConfigurationProperties` / DDL），重跑脚本。

## 文件清单

| # | 文件 | 来源 | 生成方式 |
|---|---|---|---|
| 01 | [error-codes.md](./error-codes.md) | `batch-common/.../enums/ResultCode.java` | `python3 scripts/codegen/gen-error-codes-dict.py` |
| 02 | [config-keys.md](./config-keys.md) | 各模块 `@ConfigurationProperties` | `mvn compile`（Spring Boot configuration-processor 自动生成 metadata.json） |
| 03 | [glossary.md](./glossary.md) | **手写** | 跨团队术语共识，~50 词上限 |

## 自动化机制

### error-codes.md

- 源 = `ResultCode.java` 枚举
- 脚本 = `scripts/codegen/gen-error-codes-dict.py`
- CI 校验：`python3 scripts/codegen/gen-error-codes-dict.py --check` 不一致则 fail
- 改流程：改 `ResultCode.java` → `python3 scripts/codegen/gen-error-codes-dict.py` → 提交两份

### config-keys.md

- 源 = 所有标 `@ConfigurationProperties` 的类
- 生成 = `spring-boot-configuration-processor`（编译时自动产 `target/classes/META-INF/spring-configuration-metadata.json`）
- IDE 效果：写 yml 自动补全 + 字段 javadoc 当 tooltip
- **不在仓库维护**：metadata.json 是 build artifact，每次 `mvn compile` 重生

→ 给字段加 `/** ... */` javadoc，IDE 提示就有说明文字。**这是描述配置键最优的方式**，比手写 markdown 准确且永不漂移。

## 与其他文档的关系

按 **Diátaxis 框架**（事实标准）：

| 文档类型 | 位置 | 内容性质 |
|---|---|---|
| **Reference**（dict） | `docs/dict/`（本目录）+ `docs/api/` | 值表、字段表、API 契约——"是什么 / 在哪里" |
| **Explanation** | `docs/design/` + `docs/architecture/` | 设计决策、原理——"为什么这样设计" |
| **How-to** | `docs/runbook/` | 可执行 SOP——"怎么做某件事" |
| **Tutorial** | `docs/runbook/local-development.md` | 入门导引——"30 分钟跑起来" |

**严格不混合**：dict 不解释原理，design/architecture 不重复 dict 的值表。两者互链。

## 不进 dict 的内容

- 业务术语（"任务"、"分片"等）→ 散落在各 design 文件中按上下文解释；只在跨团队对接时再考虑独立 glossary
- 字段含义（具体到表的 column）→ 走 `docs/design/data-model-ddl.md` + 表内字段注释，不重复
- 命名规约 / 编码规范 → 走 `docs/coding-conventions.md`（这是规约，不是 dict）

## 何时新增 dict 类目

只在满足**全部**以下条件时：

1. 内容是离散值表（有限可枚举），不是叙述
2. 有源代码 / 配置作为权威，**能自动生成**（手写必鬼漂移）
3. 改一处影响全项目（前后端共识需求）

如果手写 + 没有源 + 改动局部 → 不要进 dict，写在对应 design / runbook 即可。
