## 17. 项目结构、模块划分与 POM 设计
### 17.1 模块与工程结构总览

项目结构如下（`batch-console-web` 前端工程尚未实施，不纳入后端固定模块基线）：

```text
batch-platform
├── pom.xml
├── batch-common           ← 公共基础库（含 batch-defaults.yml 共享配置基线）
├── batch-trigger
├── batch-orchestrator
├── batch-worker           ← worker 模块聚合器（aggregator + 各 worker 的 parent；artifactId 不变）
│   ├── core               （artifactId 仍为 batch-worker-core，下同）
│   ├── import
│   ├── export
│   ├── process
│   ├── dispatch
│   └── atomic
├── batch-console-api
└── batch-e2e-tests        ← 端到端验收测试模块（独立 Maven 模块，非前端）
```

#### Maven 模块规划与依赖边界

#### 第三方依赖与许可证交付要求

- 后端 Maven 依赖、前端 npm 依赖、基础镜像、部署脚本中引用的第三方组件统一纳入依赖清单
- 构建产物中应保留 `THIRD-PARTY-LICENSES.md` 与 `NOTICE` 文件
- CI 阶段建议增加依赖许可证扫描、漏洞扫描、来源仓库校验
- 对协议不明确、仓库失活、来源异常的依赖，默认不进入生产基线
- 对 GPL / AGPL / 商业限制类依赖，必须在设计评审或上线评审前完成合规确认


考虑到本项目同时包含调度、编排、执行、文件处理和控制台，建议使用 **单仓多模块 Maven**。基线采用 **JDK 25 + Spring Boot 4.0.6**。生产环境建议统一运行在 **JDK 21 或 JDK 25**，开发与 CI 环境也保持同一主版本，避免字节码和依赖行为漂移。

**模块职责与依赖边界**：

- `batch-common`：公共 DTO、枚举、异常、工具类、统一配置、消息协议
- `batch-trigger`：Quartz 调度入口，只负责触发，不承载复杂编排
- `batch-orchestrator`：流程编排、状态机、分片规划、资源调度、Kafka 投递、运行态持久化
- `batch-worker-core`：Worker 公共执行框架、任务 SPI、Kafka 消费基座，不直接承载业务持久化
- `batch-worker-import`：文件接收 / 入库 / 导入运行日志
- `batch-worker-export`：数据导出 / 文件生成 / 导出运行日志
- `batch-worker-process`：系统内 SQL 加工 / 数据质量校验,WAP+bookends 5 段 + 共享 batch.process_staging
- `batch-worker-dispatch`：文件分发 / 回执处理 / 分发运行日志
- `batch-console-api`：控制台后端 API、检索接口、审计接口、配置维护接口，以及可选 AI 助手入口
- `batch-e2e-tests`：端到端验收测试模块，包含全链路 E2E 用例（Import/Export/Dispatch/Outbox/多租户并发等）及 Verifier 断言框架
- `batch-console-web`：前端工程（**尚未实施**，可选，独立于 Maven reactor）

#### 文件链路扩展设计在模块中的落位建议

为了支撑导入 / 导出 / 分发链路的“固定阶段骨架 + 配置驱动 + 插件扩展”，建议在模块中增加如下代码落位约定：

```text
batch-orchestrator
└── src/main/java/.../pipeline
    ├── definition
    │   ├── PipelineDefinition.java
    │   └── PipelineStepDefinition.java
    ├── engine
    │   ├── PipelineExecutor.java
    │   ├── ExecutionContext.java
    │   └── StepRegistry.java
    └── repository
        ├── PipelineDefinitionRepository.java
        ├── PipelineStepDefinitionRepository.java
        └── PipelineInstanceRepository.java

batch-worker-core
└── src/main/java/.../executor
    ├── WorkerTaskExecutor.java
    ├── WorkerExecutionContext.java
    └── WorkerLeaseManager.java

batch-worker-import
└── src/main/java/.../pipeline/importing
    ├── step
    │   ├── UnzipStep.java
    │   ├── CsvParseStep.java
    │   ├── HeaderValidateStep.java
    │   └── BatchLoadStep.java
    └── mapper

batch-worker-export
└── src/main/java/.../pipeline/exporting
    ├── step
    │   ├── QueryDataStep.java
    │   ├── GenerateCsvStep.java
    │   ├── UploadMinioStep.java
    │   └── RegisterAssetStep.java
    └── mapper

batch-worker-dispatch
└── src/main/java/.../pipeline/dispatch
    ├── step
    │   ├── SftpDispatchStep.java
    │   ├── ApiPushStep.java
    │   ├── MailDispatchStep.java
    │   └── PollAckStep.java
    └── mapper
```

**落位规则**：

- 链路模板主表、步骤配置表、链路实例主记录属于定义态/轻聚合配置，建议放在 `batch-orchestrator`，**与运行态相同**走 MyBatis Mapper
- 步骤运行日志、导入/导出/分发结果回写、日志检索、回执查询属于运行态，建议在对应 Worker 中使用 MyBatis
- 控制台检索接口走 MyBatis 查询 **record** / **View**，与配置维护同一持久化栈
- 具体步骤实现放在各业务 Worker；步骤注册、统一执行入口、上下文与监控放在 `batch-worker-core` 或 `batch-orchestrator`
- 不建议在控制台模块直接实现步骤逻辑，控制台只做配置维护、查询与运维入口

**统一落位原则**：

- 业务持久化 **只**使用 **`mapper/*.java + resources/mapper/*.xml`（MyBatis）**；**禁止** `repository/*.java` 形式的 Spring Data JDBC 声明式仓库
- `batch-worker` 单模块表述全部废弃，统一为 `batch-worker-core / batch-worker-import / batch-worker-export / batch-worker-process / batch-worker-dispatch`
- 运行态核心表的主写路径集中在 `batch-orchestrator` 和对应业务 Worker 中，不在多个模块重复维护

**依赖规划原则**：

- `spring-boot-dependencies` 统一托管版本，业务模块尽量不手写第三方版本
- PostgreSQL 迁移统一使用 `flyway-core + flyway-database-postgresql`
- Kafka 依赖跟随 Spring Boot 管理版本，不在业务模块单独锁定 `kafka-clients`
- AWS SDK for Java v2 在父工程统一声明版本
- 复杂 SQL 场景统一使用 `mybatis-spring-boot-starter`；**全业务模块禁止** `spring-boot-starter-data-jdbc`
- `batch-common`、`batch-worker-core` 这类纯库模块不引入 `spring-boot-maven-plugin`
- 所有可执行模块补齐 `spring-boot-starter-actuator`
- 所有需要集成测试的模块补齐 `spring-boot-starter-test`，Kafka 相关模块补 `spring-kafka-test`

**持久层分层建议**：

| 模块 | MyBatis | 说明 |
|---|---|---|
| `batch-trigger` | 可选 | 若仅承载 Quartz 触发，可只保留 JDBC；不建议堆积业务 SQL |
| `batch-orchestrator` | 是 | 编排与配置表统一 MyBatis |
| `batch-worker-core` | 否 | 仅提供执行基座，不直接持久化业务表 |
| `batch-worker-import` | 是 | 文件接收、导入日志、回写状态为主，直接走 MyBatis |
| `batch-worker-export` | 是 | 导出任务、文件生成、状态回写为主，直接走 MyBatis |
| `batch-worker-dispatch` | 是 | 分发结果、回执、重试回写为主，直接走 MyBatis |
| `batch-worker-process` | 是 | 处理链任务与状态回写为主，直接走 MyBatis |
| `batch-console-api` | 是 | 控制台读写与检索/统计统一 MyBatis |

**结构核验后的结论与微调原则**：

- 当前模块划分可以支撑工程落地，暂不调整后端固定模块数量；目录结构仅做表达收敛，不改变主模块边界。
- `batch-console-api` 不宜长期直接依赖 `batch-orchestrator` 可执行模块；更合理的做法是在现有模块内继续下沉共享应用服务与持久化接口，并在后续演进时再评估是否抽出共享层。
- `batch-worker-import` 仅在明确采用 Spring Batch 作为某些 Step 的内部执行引擎时才引入 `spring-boot-starter-batch`；普通 Worker 实现不默认引入。
- 不新增独立的 `batch-config` 模块；共享配置已通过 `batch-common/src/main/resources/batch-defaults.yml` 实现：各服务模块在 `application.yml` 中使用 `spring.config.import: "classpath:batch-defaults.yml"` 引入共享基线（datasource、kafka、mybatis mapper 路径、actuator、logging pattern、security/kms、MinIO、orchestrator base-url、Kafka topic 名称等），模块级覆盖保留在各自 `application.yml` 中。



#### 许可证准入基线

- Apache-2.0、MIT、BSD、PostgreSQL License 等宽松型许可证可进入默认白名单
- EPL / LGPL / MPL 等需在依赖清单中明确记录，不得遗漏 NOTICE、源码修改记录和再分发义务
- AGPL / GPL / 商业双许可证组件默认不进入服务端核心链路白名单，必须经法务、合规、架构三方共同评审
- 对 MinIO Server、Grafana 这类高关注许可证组件，应区分“独立基础设施部署”与“嵌入交付物/二次修改”两种使用方式分别评估
- 父工程应输出 `THIRD-PARTY-LICENSES.md`、`NOTICE`、`SBOM`，并在 CI 中阻断未知许可证依赖进入制品


#### 文件处理与工程依赖选型

为保证批量调度平台在文件导入、文件导出、文件分发等场景下具备稳定、可维护、可扩展的工程基础，除模块结构与持久层边界外，还需明确文件处理、公共工具、压缩加解密、编码处理、渠道适配等依赖选型原则。本节用于统一工程依赖基线，避免后续开发过程中出现同类能力重复造轮子、三方库选型混乱、不同模块技术实现不一致等问题。

##### 选型原则

1. **能力与场景匹配优先**  
   优先选择与本系统标准能力边界一致的依赖，而不是功能过多但与实际场景不匹配的大而全框架。

2. **平台统一优先**  
   同一类文件处理能力原则上只选用一套主实现，避免 CSV、定长、Excel、压缩、编码等能力在不同模块中使用不同技术路线。

3. **核心链路可控优先**  
   对导入、导出、分发这类批量平台核心链路，优先选择可控性高、易于排障、便于扩展的实现方式，不将关键能力完全依赖于“黑盒式”工具库。

4. **一期标准能力与二期扩展能力分层**  
   一期先落地标准能力所需依赖，二期及以后再按需扩展 BIN/二进制报文、PGP、高级编码探测、复杂渠道适配等能力，避免首期依赖过重。

5. **公共能力下沉到 `batch-common` 或 `batch-worker-core`**  
   通用工具、通用解析辅助、公共上下文、统一异常、统一日志字段等应沉淀为公共模块能力，减少业务 Worker 重复实现。

##### 公共工具层选型

本系统公共工具能力以项目内自定义 `batch-common` 为主，必要时引入少量成熟工具库作为补充。

###### 自定义公共工具能力

`batch-common` 应统一沉淀以下工具能力：

- `DateUtils`：日期、时间、业务日期处理
- `JsonUtils`：统一 JSON 序列化与反序列化封装
- `IdGenerator`：批次号、任务号、文件号、实例号生成
- `FileNameUtils`：文件命名规则、扩展名处理、对象 Key 拼接
- `ChecksumUtils`：MD5、SHA-256 等摘要计算
- `CharsetUtils`：字符集处理、BOM 识别、编码转换辅助
- `BizAssert`：业务断言与统一异常抛出
- `MaskingUtils`：日志脱敏、敏感字段展示控制
- `PathSanitizer`：路径安全校验（null/blank/`..`遍历/沙箱逃逸四类防护）；对象存储 Key 规则辅助

上述能力建议统一封装后对业务模块开放，避免业务代码中直接到处调用第三方工具类。

###### Hutool 使用原则

系统**允许有限使用 Hutool**，但不建议将核心文件链路能力建立在 Hutool 之上。  
Hutool 适合用于以下轻量场景：

- 日期与时间小工具
- 字符串处理
- 编码辅助
- 少量 Bean 拷贝
- 文件名、路径等轻量工具处理

不建议使用 Hutool 承担以下核心能力：

- CSV 标准解析与生成
- 定长文件解析与生成
- 大文件流式处理
- Excel 主链路导入导出
- 高并发批量文件主处理逻辑

因此，项目整体策略应为：  
**公共工具优先自研封装，Hutool 仅作为轻量辅助工具，不作为核心文件处理框架。**

###### 基础通用依赖建议

公共层建议统一引入以下通用依赖：

- `jackson-databind`
- `commons-lang3`
- `commons-io`
- `jakarta.validation-api`
- `slf4j-api`

其中：

- JSON 统一使用 Jackson
- 字符串、集合、判空、简易工具可使用 Commons Lang3
- 文件流、路径、IO 辅助可使用 Commons IO

##### 文件处理标准依赖选型

###### 分隔符文件（CSV / TSV / 自定义分隔符）

分隔符文件处理建议统一采用：

- **`univocity-parsers`**

选型理由如下：

- 支持 CSV、TSV、自定义分隔符
- 对引号、转义、空列、尾列、换行兼容性较好
- 可同时用于导入解析与导出生成
- 比手工 `split(',')` 更稳健，适合标准批量文件场景

系统禁止在核心分隔符文件处理逻辑中直接使用字符串 `split()` 方式进行解析。  
分隔符文件解析与生成应统一沉淀为：

- `DelimitedFileParser`
- `DelimitedFileGenerator`

由 `batch-worker-import` 与 `batch-worker-export` 复用。

###### 定长文件

定长文件建议采用**项目内自定义实现**，不强制引入大型通用框架。

建议由系统内部实现：

- `FixedWidthFileParser`
- `FixedWidthFileGenerator`

配合模板配置实现以下能力：

- 字段起始位置
- 字段长度
- 对齐方式
- 补齐字符
- 头记录 / 体记录 / 尾记录
- 记录总长度校验
- 汇总字段生成
- 定长文件导入与导出模板映射

选型理由如下：

- 定长文件通常与具体业务报文格式强绑定
- 通用库未必比项目内模板驱动实现更适合
- 有利于与现有文件模板配置、字段映射、校验规则统一

因此，一期不引入专门定长文件外部重型框架，优先采用**模板驱动 + 自研 Parser/Generator** 方案。

###### Excel 文件

Excel 导入导出建议采用：

- **Apache POI**

适用边界如下：

- 人工上传 Excel
- 中小规模 Excel 导出
- 模板驱动型报表导出
- 非主批量通道的结构化文件处理

说明：

- Excel 不建议作为核心高吞吐批量主通道
- 大规模批量交换仍应优先采用分隔符文件或定长文件

建议实现：

- `ExcelFileParser`
- `ExcelFileGenerator`

必要时支持导入模板、表头识别、sheet 选择、列映射等扩展能力。

###### XML / JSON 文件

JSON 与 XML 处理建议统一使用 Spring Boot 体系下的标准技术：

- JSON：**Jackson**
- XML：**Jackson XML** 或标准 XML 处理能力

适用场景：

- 配置文件
- 外部接口报文
- 轻量文件交换
- 文件模板元数据
- 回执、通知、事件载荷

不建议在该类场景下再额外引入多套 JSON / XML 框架。

###### BIN / 二进制报文

BIN / 自定义二进制文件不作为一期标准能力。

设计原则如下：

- 一期标准能力聚焦：
  - 分隔符文件
  - 定长文件
  - Excel
  - XML / JSON 扩展
- 二期如有明确业务需求，可通过扩展点引入：
  - `BinaryFileParser`
  - `BinaryFileGenerator`

因此，在一期工程依赖中**不强制纳入二进制报文专用库**，但在接口与扩展模型上保留能力入口。

##### 压缩、加解密、摘要与编码依赖选型

###### 压缩与解压

压缩与解压建议采用：

- JDK 标准能力
- **Apache Commons Compress**

适用场景：

- ZIP
- GZIP
- TAR
- 常见归档包预处理

建议由导入链路预处理器与导出链路后处理器统一复用：

- `ArchiveExtractor`
- `ArchiveBuilder`

对于复杂压缩格式或未来扩展格式，可在二期按需引入专门适配能力。

###### 摘要与完整性校验

文件摘要与完整性校验建议优先采用 JDK 原生能力：

- `MessageDigest`
- MD5
- SHA-256

适用场景：

- 文件去重
- 文件幂等
- 传输完整性校验
- 存储前后校验
- 分发前后对账

建议统一封装到 `ChecksumUtils`，避免业务模块散用底层 API。

###### 加解密与验签

一期建议优先采用标准 JDK / Spring 可支持的加解密能力。  
如业务场景明确需要更复杂的签名、证书或加密算法，再按需引入：

- **Bouncy Castle**

引入原则：

- 仅在存在明确业务报文加解密、验签、证书处理需求时纳入
- 不作为首期默认必须依赖

###### 编码处理

文件编码处理以 JDK NIO Charset 能力为主，统一支持以下标准编码：

- UTF-8
- UTF-8 BOM
- GBK
- GB18030

系统应统一支持以下处理能力：

- BOM 识别
- 编码转换
- 换行符归一化
- 非法字符处理
- 导入侧编码探测与告警
- 导出侧目标编码配置

复杂编码探测能力如确有需求，可在后续版本中按需引入专用探测库。  
一期不强制引入额外编码探测框架。

##### 渠道与分发适配依赖建议

###### MinIO

对象存储统一采用：

- **AWS SDK for Java v2**

适用场景：

- 文件接收暂存
- 导出文件存储
- 分发前中转
- 文件版本归档
- 失败文件留存

###### SFTP / SSH

分发渠道涉及 SFTP 时，建议引入成熟 SSH/SFTP 客户端依赖，并统一封装为 `DispatchChannelAdapter`。  
具体库选择可根据团队经验与维护性确定，但需满足以下能力：

- 连接池或连接复用
- 上传、覆盖、目录检查
- 原子 rename
- 失败重试
- 超时控制
- 远端文件存在检查

###### 邮件分发

若需支持邮件附件发送，建议按需引入：

- Jakarta Mail

该能力不应强制作为所有 Worker 的公共依赖，可仅放在 `batch-worker-dispatch` 或对应渠道适配模块中。

###### HTTP / API 推送

外部 HTTP 推送可统一采用 Spring Boot 或标准 HTTP 客户端能力。  
应支持：

- 超时控制
- 重试
- 签名头
- 幂等键
- 返回码解析
- 回执结果记录

##### 各模块 POM 依赖清单建议

###### 父工程依赖管理建议

父工程 `dependencyManagement` 建议统一收敛版本，避免子模块自行漂移。除 Spring Boot Parent 外，建议集中管理以下版本项：

- PostgreSQL Driver
- MyBatis Spring Boot Starter
- Flyway
- AWS SDK for Java v2
- univocity-parsers
- Apache POI
- Commons Compress
- Commons Lang3
- Commons IO
- Hutool（如纳入）
- Bouncy Castle（二期按需）
- Jakarta Mail（按需）

父工程职责应聚焦于：

- 统一版本
- 统一插件
- 统一 Java 编译参数
- 统一测试基线
- 统一代码规范插件

不建议在父工程中直接塞入大量业务依赖。

###### batch-common 模块依赖建议正文

`batch-common` 作为公共基础模块，建议纳入以下依赖：

- `spring-boot-starter`
- `jackson-databind`
- `commons-lang3`
- `commons-io`
- `jakarta.validation-api`
- `slf4j-api`
- `hutool-all`（可选，且仅限轻量使用）

其中：

- Jackson 用于统一 JSON 处理
- Commons Lang3 / IO 用于基础工具能力
- Validation 用于公共参数校验模型
- Hutool 仅作为少量工具补充，不应成为核心文件处理依赖

`batch-common` 不应依赖：

- MyBatis
- PostgreSQL Driver
- Kafka
- AWS SDK v2
- Apache POI
- univocity-parsers

也就是说，`batch-common` 只承载通用能力，不承载具体链路实现。

###### batch-trigger 模块依赖建议正文

`batch-trigger` 负责定时触发、手工触发、Misfire 处理和触发请求转换，建议依赖：

- `spring-boot-starter`
- `spring-boot-starter-web`（如提供触发接口）
- `spring-boot-starter-actuator`
- Quartz 相关依赖
- Kafka Producer 相关依赖
- `batch-common`

该模块不建议直接依赖：

- 文件解析与生成相关库
- AWS SDK v2
- Apache POI
- univocity-parsers

Trigger 的职责是**发起触发**，而不是执行文件处理。

###### batch-orchestrator 模块依赖建议正文

`batch-orchestrator` 负责编排、状态推进、Worker 路由、链路定义和调度决策，建议依赖：

- `spring-boot-starter`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-actuator`
- `spring-kafka`
- `mybatis-spring-boot-starter`
- PostgreSQL Driver
- Flyway
- `batch-common`

配置表与运行态表**统一**走 MyBatis Mapper；不引入 `spring-boot-starter-data-jdbc`。

该模块不建议直接引入：

- Apache POI
- univocity-parsers
- Commons Compress
- Jakarta Mail
- SFTP/SSH 客户端

即 Orchestrator 负责调度与定义，不直接处理具体文件内容。

###### batch-worker-core 模块依赖建议正文

`batch-worker-core` 作为 Worker 执行基座，建议依赖：

- `spring-boot-starter`
- `spring-boot-starter-actuator`
- `spring-kafka`
- `batch-common`

可按需引入：

- Micrometer 指标相关依赖
- Tracing / 观测相关依赖
- Retry 基础能力

该模块不建议直接引入大量文件格式依赖，特别是不建议直接绑定：

- Apache POI
- univocity-parsers
- Commons Compress
- AWS SDK v2
- Jakarta Mail

这些应下沉到具体业务 Worker 模块。`batch-worker-core` 应保持轻量，聚焦：

- Worker 注册
- 心跳与续租
- 任务拉取
- 执行包装器
- 日志与指标
- Step SPI 调度基座

###### batch-worker-import 模块依赖建议正文

`batch-worker-import` 负责导入链路，包括接收后的预处理、解析、校验、入库与反馈，建议依赖：

- `batch-worker-core`
- `batch-common`
- `mybatis-spring-boot-starter`
- `univocity-parsers`
- Apache POI
- Commons Compress
- Jackson / Jackson XML
- AWS SDK for Java v2
- PostgreSQL Driver（通常由上层统一提供）

定义类配置读取与运行日志、导入记录、状态推进**统一**建议走 MyBatis。

该模块建议内聚以下能力：

- `DelimitedFileParser`
- `FixedWidthFileParser`
- `ExcelFileParser`
- `ArchiveExtractor`
- `ChecksumUtils` 调用
- 导入链路 Step 实现

###### batch-worker-export 模块依赖建议正文

`batch-worker-export` 负责导出链路，包括数据准备、文件生成、对象存储写入、元数据登记前置处理，建议依赖：

- `batch-worker-core`
- `batch-common`
- `mybatis-spring-boot-starter`
- `univocity-parsers`
- Apache POI
- Commons Compress
- AWS SDK for Java v2
- Jackson（如模板或元数据需要）
- PostgreSQL Driver（通常由上层统一提供）

该模块建议内聚以下能力：

- `DelimitedFileGenerator`
- `FixedWidthFileGenerator`
- `ExcelFileGenerator`
- `ArchiveBuilder`
- 文件命名与对象 Key 生成
- 导出链路 Step 实现

导出模块应支持：

- 分隔符文件导出
- 定长文件导出
- 头体尾生成
- 编码/BOM/换行符控制
- 压缩打包
- 临时对象写入与正式对象切换

###### batch-worker-dispatch 模块依赖建议正文

`batch-worker-dispatch` 负责分发准备、渠道投递、回执处理与重试补偿，建议依赖：

- `batch-worker-core`
- `batch-common`
- `mybatis-spring-boot-starter`
- AWS SDK for Java v2
- SFTP/SSH 客户端
- Jakarta Mail（如支持邮件分发）
- HTTP Client / Spring Web Client
- Jackson
- PostgreSQL Driver（通常由上层统一提供）

该模块建议内聚以下能力：

- 渠道适配器 `DispatchChannelAdapter`
- SFTP 分发
- 邮件分发
- API 推送
- 回执轮询
- 回执结果登记
- 重试与补偿执行

不建议在该模块中引入导入/导出主处理所需的 POI、CSV 解析主依赖，除非确有复用场景。

###### batch-console-api 模块依赖建议正文

`batch-console-api` 负责提供控制台与运维接口，建议依赖：

- `spring-boot-starter-web`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `spring-boot-starter-jdbc`
- `mybatis-spring-boot-starter`
- `batch-common`

控制台持久化**统一** MyBatis（配置维护与复杂检索同一套 Mapper）；不引入 `spring-boot-starter-data-jdbc`。

该模块不应直接承担：

- 文件解析
- 文件生成
- 文件分发
- 大规模执行逻辑

控制台职责应聚焦于：

- 查询
- 触发
- 补偿
- 审计
- 运维操作入口
- 可选 AI 助手入口（仅限只读分析、草稿生成、发布前审查、参数建议）

###### Console 中 AI 助手能力的落位建议

AI 能力适合放在 `batch-console-api`，作为控制面增强能力，不宜落在 `batch-orchestrator`、`batch-worker-*` 或 `batch-common` 中。当前阶段**不新增 `batch-console-ai` 独立模块**，保持主模块结构不变，只在 `batch-console-api` 内增加 `ai/` 包结构。

推荐目录如下：

```text
batch-console-api
└── src/main/java/.../ai
    ├── controller
    │   └── AiAssistantController.java
    ├── service
    │   ├── FileTemplateAssistantService.java
    │   ├── IncidentAnalysisAssistantService.java
    │   ├── ConsoleQaAssistantService.java
    │   ├── ConfigReviewAssistantService.java
    │   ├── SchedulingAdviceService.java
    │   ├── ShardingAdviceService.java
    │   └── CompensationAdviceService.java
    ├── model
    │   ├── AiReviewRequest.java
    │   ├── AiReviewResponse.java
    │   ├── AiSuggestion.java
    │   └── AiRiskLevel.java
    ├── prompt
    │   ├── PromptTemplateRegistry.java
    │   └── templates
    ├── guardrail
    │   ├── AiDataMaskingService.java
    │   ├── AiPermissionGuard.java
    │   └── AiInputSanitizer.java
    └── config
        └── AiAssistantConfig.java
```

AI 助手只纳入以下 7 类场景：

1. 文件模板 / 字段映射生成助手
2. 运行异常分析助手
3. 控制台问答助手
4. 配置发布前 AI 审查
5. 调度参数优化建议
6. 分片与资源画像建议
7. 补偿方案建议

**落位边界**：

- AI 入口仅通过 Console API 暴露，不直接进入调度内核
- AI 仅返回建议、摘要、草稿或审查结论，不直接发布配置，不直接执行补偿
- AI 读取范围以脱敏元数据、运行日志、配置草稿、统计指标为主
- AI 默认不读取上下游原始文件明文；如需读取样例文件，必须使用脱敏样本或受控授权副本
- AI 请求与结果必须纳入审计，记录发起人、输入摘要、输出摘要、是否被人工采纳

**AI 依赖落位原则**：

- 父工程仅新增 Spring AI BOM，用于统一版本管理
- AI 模型接入依赖仅放在 `batch-console-api`
- `batch-orchestrator`、`batch-worker-*`、`batch-common` 不引入 Spring AI、OpenAI SDK 或其他模型 SDK
- 当前阶段不拆 `batch-console-ai`，后续只有在 AI 成为独立产品面或需要单独部署时再考虑拆分

**推荐配置项**：

```yaml
spring:
  ai:
    openai-sdk:
      api-key: ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
```

该配置建议仅出现在 `batch-console-api` 的环境配置中，不向其他模块传播。


##### 一期与二期依赖分层建议

###### 一期必须纳入的标准依赖

- PostgreSQL Driver
- Flyway
- Kafka Client
- Quartz
- MyBatis
- AWS SDK for Java v2
- Jackson
- Commons Lang3
- Commons IO
- `univocity-parsers`
- Apache POI
- Commons Compress

###### 二期按需扩展的依赖

- Bouncy Castle
- 二进制报文专用处理能力
- 复杂编码探测库
- 特殊渠道协议适配库
- 高级加密压缩处理能力

##### 本节结论

本系统文件处理与工程依赖的总体策略为：

- **公共工具以 `batch-common` 自定义封装为主**
- **Hutool 仅作轻量辅助，不承载核心文件链路**
- **分隔符文件统一采用 `univocity-parsers`**
- **定长文件采用模板驱动的自研 Parser/Generator**
- **Excel 统一采用 Apache POI**
- **JSON/XML 统一采用 Jackson 体系**
- **压缩解压统一采用 JDK + Commons Compress**
- **摘要、编码优先使用 JDK 标准能力**
- **BIN/二进制文件能力作为二期扩展，不纳入一期标准依赖**

通过统一上述依赖选型，可确保导入、导出、分发三条文件链路在工程实现层面保持一致性，并为后续工程骨架生成、模块 POM 定稿、文件处理实现和渠道适配实现提供稳定的依赖基线。


#### 父工程 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/>
    </parent>

    <groupId>io.github.pinpols.batch</groupId>
    <artifactId>batch-platform</artifactId>
    <version>${revision}</version>
    <packaging>pom</packaging>
    <name>batch-platform</name>

    <modules>
        <module>batch-common</module>
        <module>batch-trigger</module>
        <module>batch-orchestrator</module>
        <module>batch-worker-core</module>
        <module>batch-worker-import</module>
        <module>batch-worker-export</module>
        <module>batch-worker-dispatch</module>
        <module>batch-console-api</module>
        <module>batch-e2e-tests</module>
        <!-- batch-console-web 前端工程尚未实施，不纳入 Maven reactor -->
    </modules>

    <properties>
        <!-- Maven CI-friendly：统一版本入口，可通过 `-Drevision=X.Y.Z` 构建期覆盖 -->
        <revision>1.0.0</revision>
        <java.version>25</java.version>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <minio.version>8.6.0</minio.version>
        <mybatis-spring-boot.version>4.0.0</mybatis-spring-boot.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.minio</groupId>
                <artifactId>minio</artifactId>
                <version>${minio.version}</version>
            </dependency>
            <dependency>
                <groupId>org.mybatis.spring.boot</groupId>
                <artifactId>mybatis-spring-boot-starter</artifactId>
                <version>${mybatis-spring-boot.version}</version>
            </dependency>
            <dependency>
                <groupId>org.mybatis.spring.boot</groupId>
                <artifactId>mybatis-spring-boot-starter-test</artifactId>
                <version>${mybatis-spring-boot.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <release>${maven.compiler.release}</release>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

#### batch-common 模块 pom.xml

补充说明：

- 若控制台统一走上层认证网关，`spring-boot-starter-security` 仍建议保留，用于本地鉴权、方法级授权、审计上下文透传与 AI 能力权限守卫
- `spring-ai-starter-model-openai-sdk` 仅落在 `batch-console-api`；其他核心模块不引入任何 AI 依赖

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-common</artifactId>
    <name>batch-common</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

#### batch-trigger 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-trigger</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-quartz</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### batch-orchestrator 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-orchestrator</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### batch-worker-core 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-worker-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

#### batch-worker-import 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-worker-import</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-worker-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
        </dependency>

        <!-- 仅在明确采用 Spring Batch 编排本地步骤时启用 -->
        <!--
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-batch</artifactId>
        </dependency>
        -->

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### batch-worker-export 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-worker-export</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-worker-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### batch-worker-dispatch 模块 pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-worker-dispatch</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-worker-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### batch-console-api 模块 pom.xml

**说明**：`batch-console-api` 负责控制台接口与可选 AI 助手入口。AI 相关依赖只放在该模块，不向调度内核和 Worker 模块扩散。下面给的是保持现有大结构不变前提下的推荐最小可用方案。

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.pinpols.batch</groupId>
        <artifactId>batch-platform</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>batch-console-api</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.pinpols.batch</groupId>
            <artifactId>batch-orchestrator</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai-sdk</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### AI 方案 A 的最终口径

- 父工程新增 `spring-ai-bom`，统一 Spring AI 相关版本
- `batch-console-api` 新增 `spring-ai-starter-model-openai-sdk`
- 不在 `batch-orchestrator`、`batch-worker-*`、`batch-common` 中引入任何 AI 依赖
- 模块结构保持不变，AI 仅作为 Console 控制面增强能力存在
- AI 只做建议、草稿、审查与问答，不进入调度状态机和执行内核

#### batch-console-web 模块（尚未实施）

前端工程尚未启动，当前控制台功能由 `batch-console-api` 提供后端接口。若后续实施，建议独立为前端工程，不纳入 Java 多模块 reactor；若为了统一仓库管理保留在本项目中，可只保存静态资源与打包脚本，不强行作为 Spring Boot 应用。

**推荐方式 A：独立前端工程**

```text
batch-console-web/
├── package.json
├── vite.config.ts
├── src/
└── dist/
```

**推荐方式 B：由 batch-console-api 托管静态资源**

```text
batch-console-api
└── src/main/resources/static/
```

**说明**：

- 若你已有 Vue 技术栈，优先选择方式 A
- 若当前阶段要快速落地，方式 B 更轻量
- 不建议做一个“空 Java Web 模块”只为了凑 Maven 模块数

### 17.2 各模块职责与依赖边界

已实施 **8 个后端模块**（前端工程 batch-console-web 尚未实施）：

```text
batch-platform
├── batch-common           ← 公共库 + 共享配置基线（batch-defaults.yml）
├── batch-trigger
├── batch-orchestrator
├── batch-worker           ← worker 聚合器（aggregator + parent；子目录 core/import/... artifactId 不变）
│   ├── core
│   ├── import
│   ├── export
│   ├── process
│   ├── dispatch
│   └── atomic
├── batch-console-api
└── batch-e2e-tests        ← 端到端验收测试（E2E，独立模块）
```

#### batch-common

公共基础模块，放置所有服务共用内容：

- DTO
- 枚举
- 常量
- 通用异常
- 工具类
- 消息协议
- 状态定义
- 通用领域模型

建议包含：

- JobType
- JobStatus
- PartitionStatus
- TriggerType
- PriorityLevel
- TaskMessage
- LaunchRequest
- CommonResponse
- BizException

#### batch-trigger

调度触发层，负责：

- Quartz cron 触发
- 手工 / API 触发入口接入
- 统一触发请求组装
- 调用 Orchestrator 启动任务
- 记录触发历史

不负责：

- 任务分片
- 重试逻辑
- 依赖判断
- Worker 调度
- 具体业务执行

#### batch-orchestrator

系统大脑，负责：

- 任务定义管理
- 创建 job_instance
- 创建 job_partition
- 状态机推进
- 作业依赖检查
- DAG / 流程编排
- 分片生成
- 路由到 MQ
- 重试与补偿
- 汇总执行结果
- 批量窗口控制
- SLA 检查
- 资源队列与并发治理

核心原则：

- Quartz 只负责“什么时候开始”
- Worker 只负责“收到任务就执行”
- Orchestrator 负责“做什么、怎么拆、什么时候推进下一步”

#### Worker 模块组

执行层拆分为 4 个模块：

- `batch-worker-core`：执行框架与公共基础能力
- `batch-worker-import`：文件接收 / 解析 / 入库任务
- `batch-worker-export`：数据导出 / 文件生成任务
- `batch-worker-dispatch`：文件投递 / 回执 / 回传任务

**公共职责**：

- 消费 MQ
- 认领任务
- 执行业务
- 心跳上报
- 更新分片状态
- 执行结果回写
- 重试转移

**拆分原则**：

- 公共框架沉到 `batch-worker-core`
- 具体任务实现沉到各自业务 Worker 模块
- 避免把 import / export / dispatch 全部堆进一个超大执行应用

#### batch-console-api

管理控制台后端，提供：

- 任务定义管理
- cron 配置
- 手工触发
- 补跑 / 重跑
- DAG 配置
- Worker 查询
- 日志查询
- 监控视图数据接口
- 文件元数据查询
- 审计查询

#### batch-console-web

控制台前端，负责可视化：

- 任务列表
- 任务实例列表
- 分片详情
- DAG 视图
- 运行监控
- 手工操作页面
- 审计页面

---

### 17.3 推荐目录结构

#### 根项目结构

```text
batch-platform
├── pom.xml
├── README.md
├── docs
│   ├── architecture
│   ├── api
│   └── sql
├── batch-common
├── batch-trigger
├── batch-orchestrator
├── batch-worker-core
├── batch-worker-import
├── batch-worker-export
├── batch-worker-process
├── batch-worker-dispatch
├── batch-worker-atomic
├── batch-console-api
└── batch-console-web（可选）
```

#### batch-common

```text
batch-common
└── src/main/java/com/example/batch/common
    ├── constants
    │   ├── JobConstants.java
    │   └── MqTopics.java
    ├── enums
    │   ├── JobType.java
    │   ├── JobStatus.java
    │   ├── PartitionStatus.java
    │   ├── TriggerType.java
    │   └── PriorityLevel.java
    ├── dto
    │   ├── LaunchRequest.java
    │   ├── LaunchResponse.java
    │   ├── TaskMessage.java
    │   └── WorkerHeartbeatDto.java
    ├── exception
    │   ├── BizException.java
    │   └── SystemException.java
    ├── utils
    │   ├── JsonUtils.java
    │   ├── IdGenerator.java
    │   └── DateUtils.java
    └── model
        ├── PageRequest.java
        └── PageResponse.java
```

#### batch-trigger

```text
batch-trigger
└── src/main/java/com/example/batch/trigger
    ├── config
    │   ├── QuartzConfig.java
    │   └── DataSourceConfig.java
    ├── job
    │   └── QuartzLaunchJob.java
    ├── service
    │   ├── TriggerService.java
    │   ├── LaunchAdapterService.java
    │   └── TriggerAuditService.java
    ├── mapper
    │   └── TriggerHistoryMapper.java
    ├── controller
    │   └── TriggerController.java
    ├── scheduler
    │   ├── JobSchedulerManager.java
    │   └── QuartzJobFactory.java
    └── domain
        ├── TriggerHistory.java
        └── QuartzJobConfig.java
```

关键说明：

- `QuartzLaunchJob`：统一 Quartz 入口
- `LaunchAdapterService`：把 Quartz 上下文转换成 LaunchRequest
- `TriggerController`：手工 / API 触发入口
- 定义态 / 运行态目录 **统一** 使用 `mapper/*.java + resources/mapper/*.xml`（MyBatis）；**同一业务表禁止** 第二套声明式 JDBC 仓库

#### batch-orchestrator

> 注：以下为截至 2026-04-08 的实际代码结构。早期设计中的 engine/dependency/partition/retry
> 子包已整合为 application/service 下的独立服务类，不以子包划分。

```text
batch-orchestrator
└── src/main/java/com/example/batch/orchestrator
    ├── application
    │   └── service                            ← 核心业务服务（God Class 拆分后的产物）
    │       ├── LaunchValidationService.java   (接口)
    │       ├── PartitionDispatchService.java  (接口) ← T2：分区 + Outbox，独立事务
    │       ├── TaskExecutionService.java      (接口)
    │       ├── TaskCreationService.java       (接口)
    │       ├── TaskAssignmentService.java     (接口)
    │       ├── TaskOutcomeService.java        (接口)
    │       ├── RetryGovernanceService.java    (接口)
    │       ├── WorkflowDagService.java        (接口)
    │       ├── WorkflowOrchestrationService.java (接口)
    │       ├── Default***.java                ← 各接口的 Default 实现
    │       └── CompensationHandler.java
    ├── config
    ├── controller
    │   ├── LaunchController.java
    │   ├── TaskController.java                ← POST /internal/tasks/{taskId}/claim|report|renew
    │   ├── WorkerController.java
    │   ├── ApprovalController.java
    │   ├── CompensationController.java
    │   ├── DeadLetterController.java
    │   ├── FileGovernanceController.java
    │   └── SchedulerSnapshotController.java
    ├── domain                                 ← Entity / Record / 枚举 / 值对象
    ├── infrastructure                         ← Kafka Producer / Outbox 转发 / 外部 HTTP 适配 / DatabaseIdempotencyGuard（幂等层）
    ├── mapper                                 ← MyBatis Mapper 接口
    ├── scheduler                              ← @Scheduled 扫描任务（Outbox / Retry / SLA / 分片回收等）
    └── service                                ← LaunchService / LaunchValidationService 接口 + 实现（顶层入口）
```

#### Worker 模块组

##### batch-worker-core

```text
batch-worker-core
└── src/main/java/com/example/batch/worker/core
    ├── app
    │   ├── TaskDispatchExecutor.java
    │   └── WorkerRuntimeFacade.java
    ├── config
    │   ├── OrchestratorTaskClientProperties.java
    │   ├── OrchestratorWorkerClientProperties.java
    │   ├── WorkerCoreConfiguration.java
    │   └── WorkerLeaseProperties.java
    ├── domain
    │   ├── PulledTask.java
    │   ├── StepExecutionRequest.java
    │   ├── StepExecutionResponse.java
    │   ├── TaskExecutionReport.java
    │   ├── WorkerExecutionResult.java
    │   └── WorkerRegistration.java
    ├── infrastructure
    │   ├── DeadLetterPublisher.java           ← 任务死信写入 batch.task.dead-letter Topic
    │   ├── DefaultHeartbeatService.java
    │   ├── DefaultStepExecutionAdapter.java
    │   ├── DefaultTaskExecutionWrapper.java
    │   ├── DefaultWorkerLifecycleManager.java
    │   ├── DefaultWorkerRegistryService.java
    │   ├── HttpTaskExecutionClient.java
    │   ├── HttpWorkerRegistryClient.java
    │   ├── PipelineRuntimeKeys.java
    │   ├── PlatformFileRuntimeRepository.java
    │   └── WorkerTaskLeaseRenewer.java
    └── support
        ├── AbstractWorkerLoop.java           ← Worker 生命周期模板基类（注册/心跳/关闭）
        ├── AbstractTaskConsumer.java          ← Kafka 消费骨架基类（parse→claim→execute→report）
        ├── AbstractStageExecutor.java         ← pipeline 阶段执行模板基类（while 循环骨架）
        ├── AbstractPipelineStepExecutionAdapter.java
        ├── ExecutionContext.java              ← pipeline 上下文 SPI
        ├── PipelineStepFlowSupport.java       ← step flow 流转辅助
        ├── StageExecutionContext.java          ← MDC 必填字段 record
        ├── StageExecutionResult.java          ← 阶段结果 SPI
        ├── StageFailureCode.java              ← 标准失败码枚举
        ├── ActiveTaskLeaseRegistry.java
        ├── HeartbeatService.java
        ├── StepExecutionAdapter.java
        ├── TaskExecutionClient.java
        ├── TaskExecutionWrapper.java
        ├── WorkerLifecycleManager.java
        ├── WorkerRegistryClient.java
        └── WorkerSelfRegistrationService.java
```

##### batch-worker-import

```text
batch-worker-import
└── src/main/java/com/example/batch/worker/imports
    ├── config
    │   ├── BusinessDataSourceConfiguration.java
    │   ├── BusinessDataSourceProperties.java
    │   └── ImportWorkerConfiguration.java
    ├── domain
    │   ├── CustomerImportPayload.java
    │   ├── ImportJobContext.java
    │   ├── ImportPayload.java
    │   ├── ImportStage.java
    │   ├── ImportStageResult.java
    │   └── ImportWorkerType.java
    ├── infrastructure
    │   ├── CustomerAccountImportRepository.java
    │   └── ImportStepExecutionAdapter.java
    ├── route
    │   ├── DefaultImportWorkerRouteAdapter.java
    │   └── ImportWorkerRouteAdapter.java
    ├── runtime
    │   ├── ImportTaskConsumer.java
    │   └── ImportWorkerLoop.java
    └── stage
        ├── DefaultImportStageExecutor.java
        ├── FeedbackStep.java
        ├── ImportStageExecutor.java
        ├── ImportStageStep.java
        ├── LoadStep.java
        ├── ParseStep.java
        ├── PreprocessStep.java
        ├── ReceiveStep.java
        └── ValidateStep.java
```

##### batch-worker-export

```text
batch-worker-export
└── src/main/java/com/example/batch/worker/exports
    ├── config
    │   ├── BusinessDataSourceConfiguration.java
    │   ├── BusinessDataSourceProperties.java
    │   ├── ExportWorkerConfiguration.java
    │   ├── ExportWorkerDataConfiguration.java
    │   └── MinioStorageProperties.java
    ├── domain
    │   ├── ExportJobContext.java
    │   ├── ExportPayload.java
    │   ├── ExportStage.java
    │   ├── ExportStageResult.java
    │   └── ExportWorkerType.java
    ├── infrastructure
    │   ├── ExportStepExecutionAdapter.java
    │   ├── MinioExportStorage.java
    │   └── SettlementExportRepository.java
    ├── route
    │   ├── DefaultExportWorkerRouteAdapter.java
    │   └── ExportWorkerRouteAdapter.java
    ├── runtime
    │   ├── ExportTaskConsumer.java
    │   └── ExportWorkerLoop.java
    └── stage
        ├── CompleteStep.java
        ├── DefaultExportStageExecutor.java
        ├── ExportStageExecutor.java
        ├── ExportStageStep.java
        ├── GenerateStep.java
        ├── PrepareStep.java
        ├── RegisterStep.java
        └── StoreStep.java
```

##### batch-worker-dispatch

```text
batch-worker-dispatch
└── src/main/java/com/example/batch/worker/dispatchs
    ├── config
    │   ├── BusinessDataSourceProperties.java
    │   ├── DispatchWorkerConfiguration.java
    │   └── DispatchWorkerDataConfiguration.java
    ├── domain
    │   ├── DispatchJobContext.java
    │   ├── DispatchPayload.java
    │   ├── DispatchStage.java
    │   ├── DispatchStageResult.java
    │   └── DispatchWorkerType.java
    ├── infrastructure
    │   ├── channel
    │   │   ├── DispatchChannelAdapter.java
    │   │   ├── DispatchChannelGateway.java
    │   │   ├── DispatchCommand.java
    │   │   ├── DispatchResult.java
    │   │   ├── HttpDispatchChannelAdapter.java
    │   │   └── LocalDispatchChannelAdapter.java
    │   ├── DispatchStepExecutionAdapter.java
    │   └── FileDispatchRepository.java
    ├── route
    │   ├── DefaultDispatchWorkerRouteAdapter.java
    │   └── DispatchWorkerRouteAdapter.java
    ├── runtime
    │   ├── DispatchTaskConsumer.java
    │   └── DispatchWorkerLoop.java
    └── stage
        ├── AckDispatchStep.java
        ├── CompensateDispatchStep.java
        ├── CompleteDispatchStep.java
        ├── DefaultDispatchStageExecutor.java
        ├── DeliverDispatchStep.java
        ├── DispatchStageExecutor.java
        ├── DispatchStageStep.java
        ├── PrepareDispatchStep.java
        └── RetryDispatchStep.java
```

**目录统一说明**：

- MyBatis 访问层统一放 `mapper/*.java + resources/mapper/*.xml`
- **禁止** 以 Spring Data JDBC `repository/*.java` 作为业务表第二写入口；自研 `*Repository` 类须符合 ADR-001 / agent-baseline §7

#### batch-console-api

```text
batch-console-api
└── src/main/java/com/example/batch/console
    ├── controller
    │   ├── JobManageController.java
    │   ├── JobRunController.java
    │   ├── PartitionController.java
    │   ├── WorkerController.java
    │   ├── FileController.java
    │   ├── DagController.java
    │   └── AuditController.java
    ├── service
    │   ├── JobManageService.java
    │   ├── JobQueryService.java
    │   ├── PartitionQueryService.java
    │   ├── WorkerQueryService.java
    │   ├── DagManageService.java
    │   └── AuditService.java
    ├── mapper
    │   ├── JobDefinitionMapper.java
    │   ├── JobInstanceMapper.java
    │   ├── JobPartitionMapper.java
    │   ├── WorkerRegistryMapper.java
    │   └── AuditLogMapper.java
    └── dto
        ├── JobDefinitionDto.java
        ├── JobInstanceDto.java
        ├── PartitionDto.java
        └── WorkerDto.java
```

#### batch-console-web

```text
batch-console-web
├── src
│   ├── api
│   ├── views
│   │   ├── job-definition
│   │   ├── job-instance
│   │   ├── partition-detail
│   │   ├── worker-monitor
│   │   ├── dag-designer
│   │   ├── file-metadata
│   │   └── audit-log
│   ├── components
│   ├── router
│   └── store
```

---

