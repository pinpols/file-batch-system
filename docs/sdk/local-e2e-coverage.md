# SDK 本地全链路覆盖(local e2e coverage)

BYO 多语言 SDK 的"全场景覆盖"是怎么分层的、用什么测、目前到哪了。

## 覆盖策略:语言 × 场景,不是 语言 × worker 类型

SDK 自托管 worker 是 **BYO 通用**的:它的 wire 路径(register → 消费 node-direct topic
→ claim → 执行 handler → report → 优雅停)**与任务类型无关**。任务类型只决定跑哪个
用户 handler(用户代码,SDK 范围外)。所以**不做 `语言 × worker类型` 组合矩阵**。

| 关注点 | 由谁保证 | 测试层 |
|---|---|---|
| SDK wire 路径(register/消费/claim/report) | SDK,**类型无关** | conformance fixture(决策核)+ 本地全链路(真 wire,每语言 1 遍 echo) |
| "类型无关"这条性质本身 | SDK | 单测 `sdk/go/kafka/topic_match_test.go`(node-direct 匹配对 import/export/process/dispatch/atomic 全 base 命中,钉死无需每类型跑真链路) |
| import/export/process/dispatch/atomic **主链路** | 内建平台 worker | `batch-e2e-tests/*E2eIT` |
| Java/Python batteries handler(内建 import/export/dispatch 处理器) | SDK batteries | 各语言单测(Java 16 + Python 28) |
| 跨语言决策核(消息形状/版本/错误分类/offset) | conformance | 30 条 fixture × 5 语言(`sdk-contract-parity.yml`) |
| 租户业务 handler | 租户 | SDK testkit(FakeBatchPlatform) |

⇒ **conformance fixture(决策核,已绿)+ 本地全链路(每语言 1 遍,echo)+ 类型无关单测
= 充分覆盖**。

## 怎么跑

前置:本地真栈已起(orchestrator :18082 + trigger :18081 + postgres :15432 + kafka),
平台库已迁 schema,且 `atomic_shell_demo` 种子已 load(`scripts/data/load-system-test-data.sh`)。

```bash
bash scripts/local/sdk-e2e-local.sh go          # 单语言
bash scripts/local/sdk-e2e-local.sh python
KEEP=1 bash scripts/local/sdk-e2e-local.sh go   # 不清理探针(调试)
```

脚本逐阶段断言并打印:`register / dispatch / execute / report / terminal`,完事自动清理探针
数据(API key / echo job / worker_registry / node-direct topic)。

**复用**:共享逻辑全在 `scripts/lib/sdk-e2e-common.sh`(seed key / 建 echo job / 建 topic /
起样例 worker / 逐阶段断言 / 清理)。本地入口 `scripts/local/sdk-e2e-local.sh` 已 source 它;
CI 入口 `scripts/ci/run-sdk-orchestrator-e2e.sh` 可复用同一套(自己 boot 栈后调相同断言函数)。
加一门语言只在 `sdk_e2e_start_worker` 里加一个 case,本地 + CI 同时生效。

## 当前状态(per language / per stage)

> 用本地真 orchestrator 实测(非 fixture)。Go 用样例 worker 跑到 **execute** 绿。

| 语言 | 样例是否接 kafka 消费 | register | full chain | 备注 |
|---|---|---|---|---|
| Go | ✅ 已接(kafka adapter) | ✅ | ✅ **terminal SUCCESS** | #655 + #2/#3/#4 全修,本地实测绿(参照实现) |
| Python | ❌ scheduler-only(无 kafka_factory) | ✅ | 🔲 | #4 已修 + #3 本就 OK;**full chain 需先给样例接 aiokafka 消费 + node-direct pattern(#2)** |
| TypeScript | ✅ 已接(kafkajs adapter) | ✅(#655) | 🔲 | 已接消费,最接近;需 #2 pattern + 核 #3/#4 后跑脚本 |
| Java | ✅ 已接(SDK consumer) | ✅(#655) | 🔲 | **默认 pattern 是 tenant-first(`batch.task.dispatch.tenant-a.*`)= #2**;需改 node-direct + 核 #4 |
| Rust | ❌ illustrative stub | — | — | 需先把样例接到 reqwest 真 transport |

> **#2 是平台级通病**:`batch.task.dispatch.<tenant>.*`(tenant-first)这个错方案 5 语言一致沿用
> (java-spring `application.yml` 默认值即 `batch.task.dispatch.tenant-a.*`),Go 只是把它写死了。
> 正确方案是 node-direct(`...<workerType>.node.<workerCode>`,对齐内建 worker)。Java/Python/TS
> 的 pattern 可配,改默认值即可;Go 已改成自动派生。

## 这套本地全链路挖出的"SDK ↔ 真 orchestrator"漂移(fixture 触不到)

SDK 的 wire 契约此前只对 fixture / fake stub 验过,从没对真 orchestrator 验,导致**每一层**
都有漂移。已逐层定位:

| # | 层 | 现象 | 状态 |
|---|---|---|---|
| 1 | register | Go/TS 不发 `workerGroup` → `worker_registry.worker_group` NOT NULL 违约 → 注册 500 | **已修(#655)** |
| 2 | 消费 topic | SDK 订阅 `batch.task.dispatch.<tenant>.*`(tenant-first),orchestrator 派发 `...<workerType>.node.<workerCode>`(base-first)→ 收不到任何任务 | **Go 已修**(本 PR,对齐内建 worker `AbstractTaskConsumer.topicPattern()` 的 node-direct);TS/Python/Rust 待改 |
| 3 | 派单报文解码 | orchestrator 发 `taskId` 是 JSON number(BIGINT),Go 结构体期望 string → decode 失败 | **Go 已修**(本 PR,tolerant number/string);其余语言待核 |
| 4 | report | `result_summary` 是 jsonb 列(`#{resultSummary}::jsonb`),SDK 发裸人读串("echoed 0 param(s)")→ `invalid input syntax for type json` → report 500。须发 JSON 对象(对齐内建 worker `DefaultTaskExecutionWrapper` 的 `{code,message}`) | **Go + Python 已修**;TS/Java 待核 |

**结论**:Go SDK **全链路本地实测绿**(#655 + #2/#3/#4 全修,register→…→terminal SUCCESS)。
要"对外可提供",把 #2/#3/#4 在 **TS/Python/Java/Rust** 照搬修齐并各跑本地全链路至 terminal 绿
即可——这是一个独立的「SDK wire 契约重校」专项,本地全链路脚本(`sdk-e2e-local.sh <lang>`)
就是它的验收工具,Go 是已跑通的参照实现。
