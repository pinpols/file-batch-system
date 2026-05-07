# Batch Scheduling Platform - Engineering Baseline

你现在是我的项目脚手架与实现助手。  
你的目标是：严格基于既定设计文档、当前仓库现状和已接受的工程基线，持续生成一致、可编译、可落地的工程代码与配置。  
除非我明确要求或者严重不合理的设计，否则不要擅自改变架构、模块边界、技术栈、命名方式、持久层策略、运行模型和数据库边界。
如有跟设计不一致的地方先进过我的确认，确认可以之后再修改。

---

## 1. 决策优先级

当信息来源冲突时，按以下顺序决策：

1. 用户本轮最新明确指令
2. 当前仓库中已经接受并落地的工程基线
3. 项目设计文档
4. 本 `AGENT.md`

补充规则：

- 发现设计文档与当前工程落地不一致时，不要直接按记忆覆盖代码
- 先以当前仓库真实状态为准，再在输出中明确指出差异
- 不要为了“看起来更标准”而擅自重构大范围目录、命名和模块边界
- 当前仓库中的临时实现、过渡实现、明显偏离设计的实现，不自动升级为“已接受基线”
- 涉及架构主链时，必须优先检查本文件中的 `Architecture Hard Constraints`

---

## Architecture Hard Constraints

以下约束属于架构硬约束，优先级高于普通编码习惯和局部现状，不允许在未说明的情况下偏离：

- 任务分发主链固定为：`DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`
- `Orchestrator` 是唯一状态主机
- 数据库是业务状态事实来源
- Kafka 只负责异步投递、解耦和事件传播，不负责业务状态事实
- Worker 主消费方式固定为 Kafka consumer，不允许以 HTTP pull 作为主消费通道
- HTTP 只允许承担：
  - `register`
  - `heartbeat`
  - `claim`
  - `report`
- Worker 执行前必须先 `CLAIM`
- Worker 不允许绕过 `CLAIM` 直接执行任务
- Worker 不允许直接改写 `job_instance`、`workflow_run`、`workflow_node_run`
- `outbox_event` 必须与任务状态写入处于同一事务边界
- 如果当前仓库里仍保留过渡性的 `HTTP pull` 或类似 fallback，实现时只能收缩它、替换它，不能继续扩展它

---

## Forbidden Implementations

没有明确批准时，以下实现方式一律禁止：

- 禁止新增或扩展基于 `/internal/tasks/pull` 的 Worker 主消费逻辑
- 禁止把 Kafka 当成唯一状态机或唯一事实来源
- 禁止让 Worker 直接推进高层编排状态
- 禁止绕过 Outbox 直接把任务主消息发到 Kafka
- 禁止新增与设计文档不一致的第二套任务分发主通道
- 禁止为了“先跑起来”长期保留与设计文档冲突的临时主链
- 禁止用“当前代码已经这样了”作为继续偏离设计的理由

---

## Pre-change Checklist

在开始任何涉及 Trigger、Orchestrator、Worker、Outbox、Kafka、状态推进的改动前，必须先自检以下项目：

- 这次改动是否触碰任务分发主链
- 是否仍然满足 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`
- 是否仍然保持 `Orchestrator` 为唯一状态主机
- 是否引入了新的 HTTP 主消费逻辑或新的事实来源
- 是否继续遵守“配置进配置类、状态进枚举、核心常量不写魔法字符串”
- 是否新增了与设计文档冲突但未明确标注为临时方案的实现

如果任一项答案不明确，先停下来，先说明，再改代码。

---

## Conflict Handling Rule

当设计文档、当前代码、用户新指令三者出现冲突时，按以下规则处理：

- 如果当前代码是临时实现、过渡实现、明显偏离设计的实现，不允许直接沿着偏差继续补代码
- 必须先指出冲突点，明确到文件、类、链路和影响范围
- 对架构主链冲突，默认优先收敛回设计文档和本文件硬约束
- 只有在用户明确确认后，才允许保留或扩展偏离设计的实现
- 如果无法判断当前实现是不是“已接受基线”，必须先问清楚，不能自行推断
- 发现偏离后，默认动作不是“继续做完”，而是“先对齐规则再继续实现”

---

## 2. 项目定位与非目标

### 2.1 项目定位

- 项目是一个批量调度平台，不是单一业务任务程序
- 平台能力覆盖：
  - 任务触发
  - DAG 编排
  - 分片执行
  - Worker 路由
  - 文件导入
  - 文件导出
  - 文件分发
  - 补偿重试
  - 审计与运行治理

### 2.2 非目标

- 不是传统单体批处理工程
- 不是“把每个功能都拆成独立 HTTP 微服务”的细粒度微服务系统
- 不是以前端为中心的项目
- 不是为了演示而堆砌框架的样板工程

工程输出必须优先保证：

- 与设计一致
- 与当前工程一致
- 可编译
- 可落地
- 可维护

---

## 3. 固定模块结构与运行单元

### 3.1 模块固定

模块固定，不允许擅自增删、合并、重命名：

- `batch-common`
- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-core`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-process`
- `batch-worker-dispatch`
- `batch-console-api`

说明：

- 设计文档里存在 `batch-console-web` 的可选规划
- 当前仓库未落地 `batch-console-web`
- 没有明确要求时，不要擅自创建前端模块

### 3.2 模块职责固定

- `batch-common`
  - 通用枚举、异常、DTO、上下文、工具、公共协议、跨模块模型
- `batch-trigger`
  - Quartz 触发、手工/API 触发、Misfire 处理、触发请求落库与转发
- `batch-orchestrator`
  - 编排、状态推进、DAG、分片规划、Worker 路由、Pipeline 执行定义、一致性与 Outbox
- `batch-worker-core`
  - Worker 公共执行基座、注册、心跳、续租、任务拉取、统一日志/指标/异常包装、Step 执行适配
- `batch-worker-import`
  - 导入链路阶段实现
- `batch-worker-export`
  - 导出链路阶段实现
- `batch-worker-process`
  - 处理链路阶段实现（PROCESS 域 staging → target publish）
- `batch-worker-dispatch`
  - 分发链路阶段实现
- `batch-console-api`
  - 控制台后端 API、触发、补偿、补跑、审计、实例查询、文件链路查询、运维接口

### 3.3 可启动单元固定

当前应保留为独立运行进程的模块：

- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-process`
- `batch-worker-dispatch`
- `batch-console-api`

当前应作为库模块存在，不单独启动：

- `batch-common`
- `batch-worker-core`

不要把 `batch-worker-core` 再做成独立 Spring Boot 进程。

---

## 4. 固定技术栈

技术栈固定，不允许擅自替换：

- JDK 25
- Spring Boot 4.0.3
- Maven 多模块
- PostgreSQL
- Quartz
- Kafka
- MinIO
- Flyway
- MyBatis
- `JdbcTemplate`（基础设施，非默认 CRUD）

### 4.1 明确禁止

- 禁止引入 JPA / Hibernate
- 禁止擅自引入未批准的基础设施框架
- 禁止把 Kafka 当成业务状态唯一事实来源
- 禁止用缓存替代运行态数据库事实表

### 4.2 Spring Batch 约束

- 不把 Spring Batch 作为全平台核心底座
- 仅在 `batch-worker-import` 某些明确需要 chunk/step 内部编排的场景下，才可局部引入
- 无明确要求时，不要新增 `spring-boot-starter-batch`

---

## 5. 本地开发环境基线

本地基础依赖默认通过 Docker Compose 运行，当前仓库真实口径为：

- PostgreSQL：`localhost:15432`
- Kafka：`localhost:19092`
- MinIO API：`http://localhost:19000`
- MinIO Console：`http://localhost:19001`
- Redis：`localhost:16379`

### 5.1 数据库连接口径

- 平台库：`jdbc:postgresql://localhost:15432/batch_platform`
- 业务库：`jdbc:postgresql://localhost:15432/batch_business`
- 用户名：`batch_user`
- 密码：`batch_pass_123`

### 5.2 其他连接口径

- Kafka Bootstrap Servers：`localhost:19092`
- MinIO Endpoint：`http://localhost:19000`
- MinIO Bucket：`batch-dev`

### 5.3 配置要求

- 不要在代码或文档中硬编码生产地址
- 不要在业务类里写死账号密码
- 本地配置优先写入 `.env`、`application-local.yml`、`@ConfigurationProperties`

---

## 6. 数据库与 Schema 边界

### 6.1 平台库与业务库边界固定

- `batch_platform`
  - 平台元数据、运行态、编排态、一致性、Quartz 元数据
- `batch_business`
  - 业务导入/导出目标表、业务示例表、业务侧查询对象

不要把业务表示例或导入导出目标表建到 `batch_platform`。

### 6.2 Schema 边界固定

- `batch_platform.batch`
  - 平台业务表
- `batch_platform.quartz`
  - Quartz 官方 `QRTZ_*` 表
- `batch_business.biz`
  - 业务库表

Quartz 相关表只能放在 `quartz` schema，不要混入 `batch`。

### 6.3 一致性与事实来源

- 数据库是业务状态事实来源
- Kafka 是异步驱动和解耦总线，不是事实表
- DB -> MQ 一致性必须通过 `outbox_event` 落地
- 所有消费者必须幂等

---

## 7. 持久层策略固定

### 7.1 单一栈：MyBatis

- **所有**业务表（配置 / 定义 / 运行 / 实例）的 **主 CRUD 与复杂查询** 走 **`mapper/*.java` + `resources/mapper/*.xml`**（ADR-001）。
- 表行映射类型放在 `domain/entity`，统一 **`*Entity` 后缀**（`record` 或 `@Data` class）；**禁止**用 `*Record` 后缀区分「配置态 vs 运行态」。
- **禁止** `spring-boot-starter-data-jdbc`、`@EnableJdbcRepositories`、`extends CrudRepository`。
- **`JdbcTemplate`** 仅 ShedLock、极薄支撑查询等；不作为默认业务 CRUD。

### 7.2 MyBatis 必须覆盖的能力

含：状态推进 SQL、CAS、分片/重试/死信、文件链路、审计、报表、控制台配置维护涉及的表——**一律 Mapper**，不按「配置态例外」走第二套 ORM。

### 7.3 禁止项

- 禁止同一 **表** / 同一 **写路径** 上 **Repository（Spring Data 意义）+ Mapper** 双写或双主入口。
- 禁止把实体类放进 `mapper` 包（`mapper` 只放 Mapper **接口**）。
- 禁止新建 `repository/*Repository.java` 作为 **Spring Data JDBC** 声明式仓库（历史已移除）。

### 7.4 MyBatis 目录规范固定

- `mapper/*.java`：只放 MyBatis Mapper 接口
- `resources/mapper/*.xml`：只放 MyBatis XML
- `domain/entity`：表行载体（`*Entity`）
- `domain/query`：查询对象（多为 `record`）

### 7.5 名为 `*Repository` 的自研类（非 Spring Data）

- 极少数聚合访问可保留类名 `*Repository`，但必须是 **普通 Spring `@Component` / 自建类**，**不是** `org.springframework.data.repository.Repository` 子类型，且 **不得** 与同表 Mapper 形成第二写路径。

---

## 8. 核心运行模型固定

### 8.1 Trigger / Orchestrator / Worker 职责边界

- Trigger 负责：
  - 接收触发
  - 生成并落 `trigger_request`
  - Quartz 调度与 Misfire 接入
  - 将触发请求转发给 Orchestrator
- Orchestrator 负责：
  - 校验触发幂等
  - 创建 `job_instance`
  - 创建 `workflow_run`
  - 分片规划
  - 状态推进
  - Worker 路由
  - Outbox 落库
- Worker 负责：
  - 执行具体步骤
  - 回写执行结果
  - 处理执行上下文
  - 输出日志与指标

### 8.2 状态写入归属硬约束

- `trigger_request`
  - Trigger 创建
  - Orchestrator 负责受理、拒绝、去重结果回写
- `job_instance`
  - 由 Orchestrator 创建和推进
- `workflow_run` / `workflow_node_run`
  - 由 Orchestrator 创建和推进
- `job_partition`
  - 由 Orchestrator 规划
  - Worker 只能通过既定回写路径更新执行结果
- `outbox_event`
  - 由 Orchestrator 在事务内写入

不要让 Worker 直接改 Orchestrator 负责的高层状态。

### 8.3 Worker 路由策略固定

系统必须支持：

- Pipeline 级默认 Worker 路由
- Step 级 Worker 路由覆盖

Step 可声明：

- `workerType`
- `capabilityTags`
- `resourceProfile`

不要改成单一固定队列模型。

### 8.4 Pipeline 与 Step 抽象固定

必须围绕以下抽象建模：

- `PipelineDefinition`
- `PipelineContext`
- `PipelineExecutor`
- `Step SPI`
- `StepRegistry`
- `StepResult`

不要擅自发明另一套同义抽象替代这一套。

---

## 9. 文件链路模型固定

### 9.1 导入链路

- `RECEIVE`
- `PREPROCESS`
- `PARSE`
- `VALIDATE`
- `LOAD`
- `FEEDBACK`

### 9.2 导出链路

- `PREPARE`
- `GENERATE`
- `STORE`
- `REGISTER`
- `COMPLETE`

### 9.3 分发链路

- `PREPARE`
- `DISPATCH`
- `ACK`
- `RETRY / COMPENSATE`
- `COMPLETE`

### 9.4 链路实现约束

- 文件链路必须支持阶段级幂等
- 导入/导出/分发都必须有恢复点
- 不允许为了省事把所有阶段压成一个大方法

---

## 10. 包结构与分层约定

### 10.1 固定包职责

- `config`
  - 配置类、`@ConfigurationProperties`、Bean 装配
- `web` / `controller`
  - HTTP 接口入口
- `application`
  - 用例编排、应用服务、协调逻辑
- `service`
  - 历史已有的模块入口服务或轻量应用服务
- `domain`
  - 核心模型、实体、查询对象、状态机、链路抽象
- `infrastructure`
  - 技术实现、默认实现、外部组件接入
  - `batch-console-api` 中 `*ApplicationService` 的 **`Default*` 实现类** 放在本包（接口仍在 `application`）
- `mapper`
  - MyBatis 接口
- `repository`
  - **禁止** 新增 Spring Data JDBC 声明式仓库；仅历史/自研 **非** `CrudRepository` 的聚合访问类可保留在此包名（须与 §7 一致）
- `support`
  - 轻量技术辅助、解析器、帮助类、门面小组件

### 10.2 `support` 包限制

`support` 包里不要放：

- 配置属性类
- 持久化实体
- API 请求对象
- 核心业务状态机

### 10.3 HTTP 入参对象规范

- 写接口请求对象放 `web/request`
- 查接口请求对象放 `web/query`
- 控制器返回优先统一使用 `CommonResponse`

### 10.4 命名边界

命名必须显式表达语义，优先使用：

- `*Controller`
- `*Service`
- `*ApplicationService`
- `*Mapper`
- `*Repository`
- `*Executor`
- `*Handler`
- `*Step`
- `*Registry`
- `*Definition`
- `*Command`
- `*Query`
- `*DTO`
- `*Entity`
- `*Event`
- `*Properties`
- `*Configuration`

不要发明新的核心层名和语义模糊的类名。

---

## 11. 统一代码风格与编程理念

### 11.1 编程理念

- 构造器注入优先：依赖明确、便于测试
- 不可变优先：能 `final` 就 `final`
- 命令查询分离：写接口和查接口分开
- 贫血模型 + 显式服务：复杂流程放 Service / Application 层
- 接口隔离：一个类只承载一个稳定职责
- 避免过度继承：优先组合
- 小方法原则：一个方法只干一件事

### 11.2 Lombok 与 record 约束

- 默认使用 Lombok 简化样板代码
- 优先使用：
  - `@Data`
  - `@Getter`
  - `@Setter`
  - `@RequiredArgsConstructor`
  - `@Builder`
  - `@Slf4j`
- 不要在同一个类里同时保留 Lombok 自动方法和手写重复方法

分类约束：

- API 请求对象、持久化实体、配置属性类：优先用 Lombok class，默认使用 `@Data`
- 跨模块不可变值对象、稳定消息模型、简单不可变返回对象：允许使用 `record`
- 同一类目的对象不要半数用 `record`、半数用 Lombok class
- `@Builder` 默认只给 `DTO`、`Command`、`Query`、`Response`、复杂装配对象使用
- MyBatis 映射实体（`*Entity`）默认不强制加 `@Builder`
- 如果一个对象的职责是持久化映射，优先保持构造和字段显式，不要为了创建方便把它改成值对象风格

### 11.3 依赖注入约束

- 禁止字段注入
- 优先 `final` 字段 + `@RequiredArgsConstructor`
- `Controller`、`Service`、`Component`、`Configuration` 中的依赖统一走构造器注入

### 11.4 其他风格约束

- 不要让一个方法同时承担校验、编排、持久化、回写四种职责
- 不要滥用 `Map<String, Object>`，仅在参数透传、扩展字段、动态消息体场景使用
- 不要为了“优雅”引入过度抽象
- 不要一次性做大规模包重命名，除非本轮任务明确要求

### 11.5 参数封装与方法边界

- 方法参数超过 3 个时，默认优先评估是否应该封装成命令对象、查询对象或上下文对象
- 跨层传递的参数，应优先使用稳定语义对象，不要长期依赖裸 `String` / `Long` 组合
- 触发、执行、回写、补偿这类流程型方法，优先显式封装上下文，减少长参数列表
- 只有在参数非常少且语义非常单一时，才允许保留裸参数

### 11.6 设计模式使用原则

- 设计模式只在解决明确复杂度时使用
- 有明显的分支切换、策略替换、状态迁移、对象构建复杂度时，再引入对应模式
- 不要为了“结构好看”而过度抽象
- 不要把简单流程切成过多层接口、工厂、适配器和包装器

### 11.7 项目级设计模式清单

允许优先使用的模式：

- `Adapter`
  - 只用于外部协议、模块边界、第三方组件适配
  - 例如 Trigger -> Orchestrator、Worker -> Orchestrator 这类跨边界映射
- `Strategy`
  - 只用于可替换的业务规则
  - 例如 Worker 路由、重试策略、分片策略、节点选择策略
- `Template Method`
  - 只用于阶段固定、步骤变化的链路
  - 例如 导入 / 导出 / 分发链路的标准阶段执行
- `State`
  - 只用于状态迁移规则明显、转移表复杂的场景
  - 目前优先保持轻量状态机接口，不要一开始就引入重型状态机框架
- `Publisher / Outbox`
  - 只用于需要保证 DB -> MQ 一致性的场景
  - 必须结合幂等消费，不允许单独依赖 MQ 作为事实来源

谨慎使用的模式：

- `Builder`
  - 只用于构造参数较多、语义清晰的 DTO / Command / Query / Response
  - 不要默认给持久化实体加 Builder
- `Decorator`
  - 只用于横切增强，例如日志、指标、重试、审计
  - 不要为了少量重复代码引入层层包装
- `Factory`
  - 只在对象创建存在明显分支且构造逻辑复杂时使用
  - 不能替代简单的 `new` 和静态工厂

禁止倾向：

- 不要为了“面向对象正确”而强行抽象
- 不要把简单映射、简单 CRUD、简单参数传递做成模式集合
- 不要因为模式名称好听就引入额外类和额外层次
- 如果一个模式不能明显降低重复、耦合或分支复杂度，就不要用

### 11.8 设计模式重构时机

- 在主链路未稳定、核心流程仍在补齐阶段，不要为了模式调整而大规模重构
- 只有当功能闭环稳定、重复逻辑明确、分支复杂度明显上升时，才允许做模式收敛
- 模式重构优先局部进行，不要一次性横跨多个模块
- 如果当前实现已经能清晰表达边界和职责，优先保持稳定，不要提前优化
- 任何模式调整都必须以“降低复杂度”为目标，而不是追求形式上的整洁

### 11.7 变量与命名

- 变量命名必须表达业务语义，避免 `data`、`info`、`tmp`、`obj` 这类泛名
- 状态变量、布尔变量、集合变量应尽量体现用途
- 枚举值、常量、SQL 状态码、事件码必须统一命名和口径
- 不要在同一个模块里同时使用多个近义命名表示同一概念

### 11.8 编码细节

- 方法只做一件事，复杂流程拆成小方法
- 条件分支优先使用枚举或常量，不要散落魔法字符串
- null 处理必须显式，关键链路不要依赖隐式假设
- 事务边界、幂等边界、状态推进边界必须在方法级明确
- 除非确实需要动态上下文，否则不要长期依赖“万能上下文对象”

---

## 12. 配置类约定

- 所有可配置项必须通过配置类承载，优先使用 `@ConfigurationProperties`
- `@Configuration` 只负责装配 Bean，不承载业务配置字段
- 模块级外部配置必须放在 `config` 包下
- 连接地址、开关、超时、线程池、重试、路由、MQ 主题等必须外置
- 读取配置并暴露给 Spring 注入的类，统一命名为 `*Properties` 或 `*Configuration`
- 不要把配置参数散落在 `service`、`support`、`controller` 中

---

## 13. 注释、日志与异常规则

### 13.1 注释规则

注释只写有价值的信息，重点说明：

- 为什么这样做
- 约束条件
- 并发语义
- 幂等语义
- 恢复点
- 副作用

不要写：

- 废话注释
- getter/setter 注释
- “初始化对象”“设置属性”这类无效注释

### 13.2 必须优先补注释的位置

- 编排推进
- 状态机迁移
- 幂等去重
- 路由决策
- 重试 / 补偿 / 死信处理
- 事务边界
- Outbox / MQ 一致性
- PipelineExecutor
- Step SPI
- 复杂 SQL

### 13.3 日志规则

日志必须尽量带关键业务键：

- `tenantId`
- `requestId`
- `traceId`
- `instanceNo`
- `jobInstanceId`
- `partitionId`
- `pipelineInstanceId`
- `fileId`

重点记录：

- 状态迁移
- 重试
- 补偿
- 超时
- 租约获取与回收
- Worker 路由决策
- 分发结果
- 文件链路异常

### 13.4 异常规则

- 禁止吞异常
- 禁止只打印 `e.getMessage()`
- 业务异常、系统异常、校验异常分层处理
- 错误处理必须保留最小可恢复性
- 不要把所有异常都降级成通用 `RuntimeException`

---

## 14. 一致性、幂等与消息规则

### 14.1 触发与实例去重

- 触发层必须显式支持 `Idempotency-Key`
- `trigger_request` 是触发幂等入口
- `job_instance.dedup_key` 是实例级去重键
- `job_partition.idempotency_key` 是分片级幂等键

### 14.2 DB 与 MQ 一致性

- 核心路径采用 Outbox 模式
- 同事务内写业务主记录 + `outbox_event`
- 事务提交后由独立 Dispatcher 投递 Kafka
- 消费侧继续保持幂等回写

### 14.3 Kafka 协议约束

- Topic、Key、事件体结构优先复用 `batch-common` 中已有模型
- 不要各模块各自定义一套消息协议

---

## 15. 生成与修改代码时的工作方式

当我给出“本轮任务”时，你应当：

1. 严格限制在本轮任务范围内
2. 优先复用现有工程基线
3. 保持模块与命名一致
4. 先给理解与假设，再给产物
5. 优先给可编译、可落地的最小实现

额外要求：

- 不要跳过理解结果直接写代码
- 不要扩展到本轮任务之外
- 不要生成与本轮任务无关的模块
- 不要输出大量伪代码占位
- 如有不确定项，先列出假设，再按最保守方案生成

---

## 16. 质量门禁

优先级从高到低：

1. 与用户本轮要求一致
2. 与当前工程基线一致
3. 与设计文档一致
4. 代码可编译
5. 结构清晰
6. 命名稳定
7. 注释有效

代码改动后默认执行：

- 至少一次 `mvn -q compile`

如果本轮只改文档、SQL 或配置，至少保证：

- 引用路径正确
- 模块边界正确
- 命名与当前仓库一致

不要留下以下半成品状态：

- 包已移动但 import 未修
- XML 还引用旧包名
- 配置已经外移但代码还在硬编码
- 新旧命名并存且没有迁移边界

---

## 17. 明确禁止项

- 引入 JPA / Hibernate
- 擅自改动模块结构
- 发明新的核心框架
- 在未要求时顺手生成前端
- 在未要求时生成无意义测试
- 输出大量伪代码占位实现
- 跳过数据库 / 消息 / 对象存储边界约束
- 修改既定 Worker 路由策略
- 修改既定 Pipeline / Step 模型
- 把 `batch-worker-core` 再做成可启动进程
- 把 MyBatis 实体继续塞回 `mapper` 包
