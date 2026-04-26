# 技术栈与设计原则

> 拆自 mega 设计文档 ch.3。版本以 `pom.xml`（根级 `<revision>${revision}</revision>`）为准；新增依赖须同步更新本文件 + `docs/compliance/THIRD-PARTY-LICENSES.md`。

## 1. 技术栈（截至 2026-04-26）

| 类别 | 技术 | 版本 | 使用模块 |
|------|------|------|---------|
| 框架 | Spring Boot | 4.0.3 | all |
| 语言 | Java | 25 | all |
| 持久化（定义层） | Spring Data JDBC | managed | orchestrator, console-api |
| 持久化（运行时层） | MyBatis Spring Boot Starter | 4.0.0 | orchestrator, workers, trigger, console-api |
| 数据库迁移 | Flyway | managed | all |
| 数据库 | PostgreSQL（JDBC Driver） | managed | all |
| 消息队列 | Apache Kafka（Spring Kafka） | managed | orchestrator, worker-core, workers |
| 对象存储 | MinIO Java SDK | 8.6.0 | common, orchestrator, workers |
| 分布式缓存 / SSE | Spring Data Redis (Lettuce) | managed | orchestrator, console-api |
| 分布式锁 | ShedLock (JDBC + Redis) | 6.3.0 | common |
| 调度（默认） | Quartz Scheduler (JDBC JobStore) | managed | trigger |
| 调度（opt-in） | Netty HashedWheelTimer | managed | trigger（`batch.trigger.scheduler-impl=wheel`） |
| 安全 | Spring Security + OAuth2 JOSE | managed | console-api |
| HTTP 客户端 | OkHttp | 4.12.0 | export, dispatch |
| 电子表格 | Apache POI | 5.4.0 | import, export, console-api |
| SQL 解析 | JSqlParser | 4.5 | export |
| SFTP | JSch (mwiede fork) | 0.2.23 | dispatch |
| 邮件 | Angus Mail | managed | dispatch |
| 校验 | Hibernate Validator | managed | orchestrator |
| 指标监控 | Micrometer + Prometheus Registry | managed | all |
| 分布式追踪 | Micrometer Tracing → OpenTelemetry OTLP | managed | common |
| 监控可视化 | Grafana | — | 独立运维基础设施 |
| 日志 | Logback + SLF4J | managed | all |
| AI 控制台增强 | Spring AI + OpenAI Starter | 2.0.0-M3 | console-api（仅控制面） |
| 代码生成 | Lombok | 1.18.42 | all (provided) |
| 测试容器 | Testcontainers (PostgreSQL + Kafka + MinIO + Redis) | 1.21.4 | all (test) |
| 邮件测试 | GreenMail | 2.1.8 | dispatch (test) |
| HTTP Mock | MockWebServer | 4.12.0 | worker-core, trigger (test) |

> **AI 边界**：AI 能力**只**落在 `batch-console-api`，不进入 orchestrator / worker / trigger / common，不参与调度状态机推进和执行内核。

## 2. 持久层选型原则（双 ORM）

混合方案。同一表只能有一个主写入口，**不允许 Repository + Mapper 双写**。

- **Spring Data JDBC** → 定义态 / 配置态 / 字典态 / 小聚合
  - 强调模型清晰、CRUD 简洁、开发成本低
  - 目录：`repository/*.java`
- **MyBatis** → 运行态 / 实例态 / 文件链路态 / 调度推进 / 补偿 / 审计 / 报表
  - 需要精确控制 SQL、批量更新、状态机推进、租约抢占、分区扫描
  - 目录：`mapper/*.java + resources/mapper/*.xml`

**推荐落位**：

| 数据类别 | 推荐技术 | 典型对象 |
|---|---|---|
| 任务定义 / 流程定义 / 资源配置 / 告警规则 | Spring Data JDBC | `job_definition`、`workflow_definition`、规则与模板类表 |
| 任务实例 / 分片实例 / 重试 / 死信 / 文件流转 / 审计日志 | MyBatis | `job_instance`、`job_partition`、`retry_schedule`、`dead_letter_task`、`file_record` |
| 控制台复杂筛选与统计 | MyBatis | 实例中心、文件中心、告警中心、SLA 检索 |
| 小型聚合维护 | Spring Data JDBC | 模板主表 + 子项、规则主表 + 子项 |

> 决策来源：[`../architecture/adr/ADR-001-dual-orm.md`](../architecture/adr/ADR-001-dual-orm.md)。

## 3. 开源协议与合规

### 3.1 协议风险分类

| 协议类型 | 风险 | 使用建议 |
|---|---|---|
| Apache-2.0 / MIT / BSD 类 | 低 | 优先采用，注意保留版权与 NOTICE |
| LGPL / MPL 类（弱 Copyleft） | 中 | 评估后使用，注意动态链接、修改回传、分发边界 |
| GPL / AGPL 类（强 Copyleft） | 高 | 默认禁止进入服务端核心，须法务审批 |
| 商业双许可证 | 中高 | 必须确认当前用的是哪个发行版、是否有商业限制 |

### 3.2 当前依赖中的高风险条目

| 组件 | 协议 | 风险 | 当前处置 |
|---|---|---|---|
| **MinIO Server**（社区版） | AGPL-3.0 / 商业许可 | 🔴 高 | 生产前须完成合规评估；可换 AWS S3 / 阿里云 OSS / 私有部署商业版 |
| **Grafana** | AGPL-3.0 | 🔴 高 | 独立运维基础设施；禁止二次修改闭源 |
| **Logback** | EPL-1.0 / LGPL-2.1 | 🟡 中 | 已在许可证清单中标注 |
| **Angus Mail** | EPL-2.0 / GPL-2.0 CE | 🟡 中 | dispatch 模块；标注双许可 |

> 完整清单：[`../compliance/THIRD-PARTY-LICENSES.md`](../compliance/THIRD-PARTY-LICENSES.md)（机器生成）+ [`../compliance/sbom.json`](../compliance/sbom.json)（CycloneDX）

### 3.3 落地原则

- 优先选择宽松型协议组件
- 服务端核心链路默认避免引入强 Copyleft 依赖
- 直接依赖、传递依赖、前端 npm、容器基础镜像都纳入检查范围
- 升级版本时不仅查兼容性，也要重新核验协议是否变化
- 自行修改过的开源组件，保留修改记录、版本基线和源码来源

### 3.4 交付物要求

| 文件 | 状态 |
|---|---|
| `THIRD-PARTY-LICENSES.md`（依赖与许可证清单） | ✅ `docs/compliance/THIRD-PARTY-LICENSES.md`（2026-04-26 更新） |
| `SBOM`（CycloneDX 或 SPDX） | ✅ `docs/compliance/sbom.json`（266 个 transitive 依赖，2026-04-26 重生成） |
| `NOTICE`（保留版权与声明） | ✅ `/NOTICE`（指向 compliance 文件） |
| `DEPENDENCY-APPROVAL.md`（高风险依赖审批记录） | 🟡 待补（仅引入 MinIO Server / Grafana 等 AGPL 组件时需要） |

**重生成命令**：
```bash
mvn -P compliance package -DskipTests
cp target/bom.json docs/compliance/sbom.json
# THIRD-PARTY-GENERATED.txt 已写到 docs/compliance/ 供对照人读版
```

## 4. 外部模型服务接入合规（OpenAI）

Spring AI / OpenAI SDK 是开源组件，但 OpenAI API 调用本身**不是仅开源协议问题**——还涉及外部商业服务接入、数据出域、密钥管理与审计留痕。两类必须分开管理。

### 4.1 控制要求

- `OPENAI_API_KEY` 仅允许通过环境变量、密钥管理系统或受控配置中心注入；**禁止**明文写入源码、镜像层、公开配置文件
- 发送到模型侧的数据必须先经过**租户权限校验、字段裁剪、脱敏**，不得直接送原始上下游文件 / 完整报文 / 跨租户上下文
- 生产 / 测试 / 开发环境用**不同的模型密钥**和访问策略，禁止共用生产密钥
- AI 请求与响应摘要**纳入审计**：发起人、租户、用途、输入类别、输出类别、是否被人工采纳
- 模型服务不可用 / 超时 / 限流时**降级策略**避免影响控制台主流程
- 高敏租户或强监管业务可配置完全关闭 AI 出域，或改为本地化 / 私有化模型

### 4.2 当前实现

| 控制面组件 | 现实位置 |
|---|---|
| `ConsoleAiPromptGuard` | `batch-console-api/.../service/ConsoleAiPromptGuard.java` |
| `ConsoleAiAuthorizationService` | `batch-console-api/.../service/ConsoleAiAuthorizationService.java` |
| `ConsoleAiAuditService` | `batch-console-api/.../infrastructure/DefaultConsoleAiAuditService.java` |
| `AiPromptGateResult` | `batch-console-api/.../support/AiPromptGateResult.java` |

文档口径：

- Spring AI / OpenAI SDK → 第三方依赖与许可证清单
- OpenAI API → 外部服务接入合规、数据出域、密钥管理
- **不能混写**为同一类"开源协议问题"

## 5. 自研代码许可证建议

### 场景 A：企业内部 / 商业闭源交付

不要把整套系统直接定义为开源项目；用公司内部版权声明 + 商用许可（EULA）+ 第三方依赖清单的组合。

- 自研代码保持公司版权所有
- 第三方组件按各自许可证履行 NOTICE / LICENSE / 源码获取义务
- AGPL 类组件单独评估，避免影响交付物边界

### 场景 B：计划开源

推荐 **Apache-2.0**：

- 与 Spring Boot / Spring Data JDBC / MyBatis / Quartz / Kafka / Flyway / Prometheus 主依赖方向一致
- 允许商业使用、修改、再分发，适合平台型基础设施项目
- 便于后续引入企业扩展模块、插件机制和二次开发生态

### 结论

- **企业内部 / 商用**：闭源商用许可 + 第三方许可证履约，不单独声明为开源
- **计划开源**：Apache-2.0
- **无论是否开源**，只要继续用 MinIO Server 社区版或 Grafana 等 AGPL 组件，都要在设计评审和上线评审中单列合规检查项

## 相关文档

- [`../architecture/architecture-truth.md`](../architecture/architecture-truth.md) — 真实架构基线
- [`../architecture/adr/`](../architecture/adr/README.md) — 架构决策记录（含 ADR-001 双 ORM 决策）
- [`../compliance/THIRD-PARTY-LICENSES.md`](../compliance/THIRD-PARTY-LICENSES.md) — 第三方许可证清单
- [`../compliance/sbom.json`](../compliance/sbom.json) — SBOM
- [`../runbook/security-scan.md`](../runbook/security-scan.md) — 安全扫描 SOP
- [project-structure-pom.md](./project-structure-pom.md) — 模块结构与 POM 设计
