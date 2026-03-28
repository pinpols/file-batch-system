# dialog-1

本文档用于记录本项目从设计文档到工程骨架的对话式落地步骤，方便后续继续补齐实现时保持节奏一致。

## Step 1 - 工程骨架

- 目标：生成多模块 Maven 工程骨架
- 内容：根目录结构、parent pom、各子模块 pom、基础 package 目录、`application.yml`
- 约束：不生成业务实现代码
状态：已完成（2026-03-06）
## Step 2 - 数据库脚本

- 目标：生成 PostgreSQL + Flyway 首版数据库脚本
- 内容：schema 规划、核心表 DDL、主键/外键/唯一约束/索引、审计字段规范、Quartz 与业务 schema 边界
- 结果：平台库、业务库和 Quartz schema 边界已经明确
状态：已完成（2026-03-06）
## Step 3 - 公共模块

- 目标：生成 `batch-common`
- 内容：通用枚举、异常、返回对象、上下文对象、工具类、审计基类
- 约束：不生成业务逻辑
状态：已完成（2026-03-06）
## Step 4 - 持久层骨架

- 目标：生成持久层骨架
- 策略：配置定义类表用 Spring Data JDBC；运行态、实例态、复杂查询用 MyBatis + XML
- 内容：DO/Entity、Repository、Mapper、XML、基础查询对象
状态：已完成（2026-03-06）
## Step 5 - Trigger 模块

- 目标：生成 `batch-trigger`
- 内容：Quartz 配置、Trigger 注册与加载、Misfire 入口、触发请求到 Orchestrator 的适配层
- 约束：不写 Orchestrator 业务代码
状态：已完成（2026-03-06）
## Step 6 - Orchestrator 基础骨架

- 目标：生成 `batch-orchestrator` 的 domain / application / infrastructure 分层
- 内容：pipeline definition / step definition、worker 路由模型、状态机基础接口、调度推进器接口与空实现
状态：已完成（2026-03-06）
## Step 7 - Pipeline 执行引擎

- 目标：生成文件链路与任务链路执行引擎骨架
- 内容：PipelineDefinition、PipelineContext、PipelineExecutor、Step SPI、StepRegistry、StepResult、默认执行顺序模型
- 约束：支持 Pipeline 默认 Worker 路由和 Step 级 Worker 覆盖
状态：已完成（2026-03-06）
## Step 8 - Worker Core

- 目标：生成 `batch-worker-core`
- 内容：Worker 注册、心跳与续租、任务拉取、执行包装器、统一日志/指标/异常处理、Step 执行适配器
- 约束：不生成 import/export/dispatch 具体步骤实现
状态：已完成（2026-03-06）
## Step 9 - Import Worker

- 目标：生成 `batch-worker-import`
- 内容：导入链路标准阶段骨架、Receive/Preprocess/Parse/Validate/Load/Feedback Step 默认实现、IMPORT workerType 与路由适配、配置样例
状态：已完成（2026-03-06）
## Step 10 - Export Worker

- 目标：生成 `batch-worker-export`
- 内容：导出链路标准阶段骨架、Prepare/Generate/Store/Register/Complete Step 默认实现、EXPORT workerType 与路由适配、配置样例
状态：已完成（2026-03-06）
## Step 11 - Dispatch Worker

- 目标：生成 `batch-worker-dispatch`
- 内容：分发链路标准阶段骨架、Prepare/Dispatch/Ack/Retry/Compensate/Complete Step 默认实现、DISPATCH workerType 与路由适配、配置样例
状态：已完成（2026-03-06）
## Step 12 - Console API

- 目标：生成 `batch-console-api`
- 内容：任务触发接口、补偿/补跑接口、审计查询接口、文件链路查询接口、运行态实例查询接口
- 策略：复杂查询走 MyBatis，简单配置维护可走 Spring Data JDBC
状态：已完成（2026-03-06）
## Step 13 - Kafka 消息模型

- 目标：统一 Kafka Topic 规划与消息体模型
- 内容：Topic 命名、Key 设计、事件体结构、重试/死信设计、幂等键设计
- 结果：消息协议已收口到 `batch-common` 的统一模型
状态：已完成（2026-03-06）
## Step 14 - 错误码与 API 协议收口

- 目标：补齐统一错误码、接口返回语义和幂等头透传规则
- 内容：错误码枚举、异常到响应映射、`Idempotency-Key` 约定、控制台接口请求响应样例
- 约束：先统一协议，不急着扩业务逻辑
状态：已完成（2026-03-06）
## Step 15 - Trigger 到 Orchestrator 的真实接入

- 目标：把 `batch-trigger` 从空适配层补成真实调用链
- 内容：Trigger 请求入库、触发定义加载、手工/API/定时触发统一转 `LaunchRequest`
- 结果：触发请求能正式进入 Orchestrator
状态：已完成（2026-03-06）
## Step 16 - Orchestrator 触发受理与实例创建

- 目标：补齐触发受理、幂等校验和实例创建主链路
- 内容：`trigger_request` 去重、`job_instance` 创建、`workflow_run` 初始化、基础状态推进
- 约束：由 Orchestrator 独占主状态推进权
状态：已完成（2026-03-06）
## Step 17 - 分片规划与 Worker 路由实现

- 目标：把骨架级路由和分片规划补成可运行实现
- 内容：分片规则、`job_partition` 生成、Pipeline 默认 Worker 路由、Step 级覆盖路由、目标队列决策
- 结果：实例创建后可形成可派发分片
状态：已完成（2026-03-06）
## Step 18 - Outbox Dispatcher 与 Kafka Producer

- 目标：补齐 DB 到 MQ 的一致性桥梁
- 内容：`outbox_event` 写入、Outbox Dispatcher 扫描、Kafka Producer 配置、投递结果回写
- 约束：禁止绕过 Outbox 直接在主事务里发 MQ
状态：已完成（2026-03-06）
## Step 19 - Worker Consumer、认领、租约与结果回写

- 目标：把 Worker 执行基座补成真正可消费消息的执行框架
- 内容：Kafka Consumer、分片认领、租约续约、执行结果回写、迟到结果拒绝策略
- 当前实际状态：已改为 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT` 主链；HTTP `pull` 不再作为主消费通道
- 结果：已具备最小可运行的消费闭环，但租约续约调度、超时回收、统一重试治理仍未补齐
状态：已完成（2026-03-06）
## Step 20 - Import Worker 真实链路实现

- 目标：补齐导入链路的最小可运行版本
- 内容：接收、预处理、解析、校验、入库、反馈各阶段真实实现；业务表写入策略；幂等导入
- 约束：先做单文件、单模板最小闭环
状态：已完成（2026-03-06）
## Step 21 - Export Worker 真实链路实现

- 目标：补齐导出链路的最小可运行版本
- 内容：数据准备、文件生成、对象存储写入、文件登记、状态回写
- 约束：先做单模板导出，控制变量
状态：已完成（2026-03-06）
## Step 22 - Dispatch Worker 真实链路实现

- 目标：补齐分发链路的最小可运行版本
- 内容：分发准备、渠道投递、回执查询/轮询、重试补偿、分发记录回写
- 结果：文件链路闭环从“生成”走到“投递完成”
状态：已完成（2026-03-06）
## Step 23 - Retry 与 Dead Letter Manager

- 目标：把失败治理从空模型补成正式能力
- 内容：`retry_schedule` 调度、指数退避、死信转移、死信重放入口、回放幂等保护
- 约束：重试和补偿命令必须有唯一命令号
状态：已完成（2026-03-06）



## Step 24 - Console API 实际查询与操作实现 

- 目标：让控制台接口真正可用
- 内容：触发、补偿、补跑、实例检索、文件链路检索、审计检索、分页筛选、基础运维接口
- 策略：复杂查询继续走 MyBatis
- 当前实际状态：已补触发/补偿/补跑代理入口，实例与文件查询已可用，审计查询已走 MyBatis；但高级运维接口、权限与审批流仍未落地
状态：已完成（2026-03-06）
- 
