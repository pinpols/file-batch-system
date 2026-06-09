# CDC → Vector → RAG 实时同步系统 · 设计文档

> 工作代号:**cdc-vector-rag**
> 版本:v0.1(初稿) · 日期:2026-06-09
> 状态:设计中,准备进入 MVP 搭建

把业务数据库的变更(CDC)**实时**同步进向量数据库,作为 RAG / 语义检索的实时数据底座。
区别于「定时全量重建索引」的传统做法:数据一改,向量秒级更新,删除即失效。

---

## 1. 目标与范围边界

### 1.1 目标(做什么)

- 监听 MySQL / PostgreSQL 的行级变更(INSERT / UPDATE / DELETE)
- 将「可检索文档」抽取、切分、向量化后写入向量库
- 提供 RAG / 语义搜索 API(检索 → 重排 → 生成)
- 全程**幂等**:同一条变更重放结果一致;数据删除则向量同步失效

### 1.2 范围边界(不做什么)

明确划线,避免项目无限膨胀:

| ✅ 做 | ❌ 不做 |
|---|---|
| 单库 / 单数据源的 CDC → 向量同步 | 多源异构数据融合 / 数据湖 |
| 行级变更驱动的增量同步 | 通用 ETL / 数据治理平台 |
| 向量检索 + 基础 RAG 问答 | 复杂 Agent 编排 / 多轮对话记忆 |
| 单表文档 + 简单跨表反查拼接 | 流式多表 JOIN(交给后续可选的 Flink CDC 阶段) |
| 多租隔离(payload 过滤) | 行级权限 / 细粒度 ACL |

> **学习目标定位**:本项目核心是练 **CDC(Debezium/WAL)+ Embedding + 向量检索 + RAG** 这套新技术栈,不是再造一个分布式批处理系统。凡是会把项目拖向「重运维平台」的需求(Flink 集群、K8s 调度、多源融合),一律推迟或砍掉。

---

## 2. 总体架构

```
┌─────────────────┐
│ 业务数据库       │  MySQL binlog / PostgreSQL WAL(逻辑复制槽 pgoutput)
│ MySQL / Postgres│
└────────┬────────┘
         │ 变更事件(c=create / u=update / d=delete / r=snapshot)
         ▼
┌─────────────────┐
│ Debezium        │  Kafka Connect 模式,standalone 起步
│ (Kafka Connect) │
└────────┬────────┘
         ▼
┌─────────────────┐
│ Kafka CDC Topic │  每表一个 topic:cdc.<db>.<table>
└────────┬────────┘
         ▼
┌──────────────────────────────────────────────┐
│ Vector Sync Worker(本项目核心)              │
│  1. 变更分流:c/r→upsert  u→re-embed  d→delete│
│  2. 文档构建:字段拼接 / 跨表反查 / 文本抽取   │
│  3. 源文本 hash 比对 → 未变则跳过 embedding   │
│  4. Chunk 切分                                │
│  5. 确定性向量 ID(由 PK 派生)               │
│  6. 批量 Embedding                            │
│  7. upsert / delete 向量库                    │
└────────┬─────────────────────────────────────┘
         ▼
┌─────────────────┐
│ 向量数据库       │  MVP: pgvector → 进阶: Qdrant
│                 │  payload: tenant_id + 业务过滤字段
└────────┬────────┘
         ▼
┌─────────────────┐
│ RAG 服务         │  检索 → (可选)rerank → Claude 生成
│  /search /ask   │
└─────────────────┘
```

### 2.1 设计原则

1. **解耦**:CDC、同步、向量库、RAG 之间靠 Kafka / HTTP 解耦,任一环节可独立替换(例如后期把文档拼接换成 Flink CDC,只改 Sync Worker)。
2. **幂等优先**:全链路按「至少一次投递」设计,靠确定性 ID + upsert 保证最终一致。
3. **成本敏感**:Embedding 是最贵的一环,默认所有变更先做 hash 去重再决定是否调用。
4. **小步可跑**:MVP 先打通最窄闭环,难点(UPDATE/DELETE、跨表、rerank)分阶段加。

---

## 3. 组件设计

### 3.1 CDC 采集层(Debezium)

- **PostgreSQL**:逻辑复制(`pgoutput` 插件),需 `wal_level=logical`,创建 replication slot + publication。
- **MySQL**:binlog(`ROW` 格式),需开启 binlog 并授予 `REPLICATION SLAVE` 权限。
- **快照策略**:`snapshot.mode=initial`——首次全量快照(事件类型 `r`)后转增量流(`c/u/d`)。
- **Topic 命名**:`cdc.<server>.<schema>.<table>`,每表一个 topic,key = 主键。
- **Schema 变更**:Debezium 自带 schema 演进;DDL 变更通过 schema history topic 跟踪。MVP 阶段约定上游不做破坏性 DDL,后续再做兼容策略。

> 关键学习点:replication slot 不消费会撑爆磁盘 WAL;snapshot 与 streaming 的衔接点(LSN / GTID);Debezium 的 `before` / `after` 镜像。

### 3.2 Kafka 层

- 每张表一个 CDC topic,分区数按吞吐设定(MVP 可 1~3 分区)。
- key 用主键 → 保证同一行的变更进同一分区,**有序**(避免 UPDATE 乱序覆盖)。
- 配 DLQ topic(`cdc.dlq`)收容处理失败的事件。

### 3.3 Vector Sync Worker(核心)

整条管道最难、最值得做的部分。处理流程见架构图 1~7 步。下面是几个硬问题的设计:

#### (a) 变更类型分流

| Debezium op | 含义 | 处理 |
|---|---|---|
| `r` | snapshot 读 | 当作 upsert |
| `c` | INSERT | 构建文档 → embed → upsert |
| `u` | UPDATE | 比对 hash:变了则删旧 chunk + 重新 embed upsert;没变跳过 |
| `d` | DELETE | 按 PK 删除该行的所有 chunk 向量 |

#### (b) 确定性向量 ID(幂等基石)

向量 ID 由业务主键派生,**不用随机 UUID**:

```
vector_id = sha256(f"{tenant_id}:{table}:{primary_key}:{chunk_index}")
```

好处:同一行重放 → 同一组 ID → upsert 天然幂等;删除时按 `tenant_id:table:pk` 前缀能定位并删掉这行的全部 chunk。

> 实现注意:一行更新后 chunk 数量可能变化(文本变长/变短)。删除时**不能**只删 `chunk_index` 已知的几个,要么先按行删全部再写新的,要么记录该行上次的 chunk 数。MVP 采用「先删该行所有 chunk → 再写新 chunk」最稳。

#### (c) 源文本 hash 去重(成本核心)

```
source_text = concat(可检索字段...)
text_hash   = sha256(source_text)
```

变更进来先查该行上次的 `text_hash`(存在一张小元数据表 / KV 里),相同则直接跳过 embedding 与写入。生产中可省 80%+ embedding 调用。

#### (d) 文档构建与字段策略

- **embed 字段**:文本类(标题 / 描述 / 正文)拼接成 `source_text`。
- **payload(metadata)字段**:结构化字段(`tenant_id` / `status` / 时间 / 分类)存进向量库 payload,用于**先过滤后召回**。
- **跨表拼接**:MVP 用「收到变更后反查一次 DB」把关联数据拉全(简单、够用)。仅当吞吐压力大、需要实时流 JOIN 时,才评估引入 Flink CDC——架构已解耦,替换成本低。

#### (e) Chunk 切分

- 按 token 长度切(如 512 token,overlap 50),保留来源 PK / 字段定位。
- 每个 chunk 一条向量记录,共享同一行的 payload + 各自 `chunk_index`。

#### (f) 与现有批处理经验的复用

Sync Worker 可照搬 `batch-worker-*` 的 **CLAIM → EXECUTE → REPORT** 幂等思路:消费即「认领」,处理完提交 offset 即「上报」,失败进 DLQ 重试。分布式经验在这里直接变现。

### 3.4 Embedding 层

- **重要**:Claude **没有** embeddings 接口。Embedding 需单独选模型,Claude 只负责 RAG 最后的生成。
- 候选:Voyage AI(Anthropic 推荐的 embedding 合作方)/ 开源 bge、e5 系列(可本地部署,省钱、练部署)。
- **批量调用**:Worker 攒一批 chunk 一次性请求,提升吞吐、降成本。
- **维度固定**:选定模型后向量维度锁死,换模型 = 全量重建索引(写进运维手册)。

### 3.5 向量数据库

**MVP 用 pgvector,进阶切 Qdrant。**

| | pgvector | Qdrant |
|---|---|---|
| 起步成本 | 极低(你已有 PG) | 需多起一个服务 |
| 过滤检索 | SQL where + 向量 | 原生 payload filter + HNSW 调优 |
| 学习价值 | 快速跑通 | 学专用向量库的工程细节 |
| 建议 | 第一阶段 | 第二阶段 |

**Schema(pgvector 示例)**:

```sql
CREATE TABLE doc_vectors (
    vector_id   TEXT PRIMARY KEY,          -- 确定性 ID
    tenant_id   TEXT NOT NULL,             -- 多租过滤
    source_table TEXT NOT NULL,
    source_pk   TEXT NOT NULL,
    chunk_index INT  NOT NULL,
    text_hash   TEXT NOT NULL,             -- 去重用
    content     TEXT NOT NULL,             -- 原文 chunk(便于召回展示)
    metadata    JSONB,                     -- 业务过滤字段
    embedding   vector(1024),              -- 维度随模型
    updated_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ON doc_vectors USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON doc_vectors (tenant_id, source_table, source_pk);
```

> 多租隔离:所有检索 **必须** 带 `tenant_id` 过滤(沿用你对多租的纪律),先按 tenant 过滤再向量召回。

### 3.6 RAG 服务

接口:

- `POST /search` — 纯语义搜索:query → embed → 向量召回(带 tenant 过滤)→ (可选)rerank → 返回 chunk 列表。
- `POST /ask` — RAG 问答:上面召回结果作为上下文 → Claude(Opus 4.8 / Sonnet 4.6)生成答案,带引用来源。

链路:`query embedding → 向量召回 topK → rerank 重排 topN → 拼 prompt → Claude 生成 → 附 source`。

> rerank 是 RAG 质量提升最明显的一步(召回拿 topK=50,重排后取 topN=5 喂模型),MVP 可先不做,第二阶段加。

---

## 4. 关键数据流(端到端示例)

**UPDATE 一行 `article` 表:**

```
1. Debezium 捕获 WAL → 发 Kafka(op=u, after={id:42, title:"新标题", body:"..."})
2. Sync Worker 消费(key=42,保证有序)
3. 构建 source_text = title + body;算 text_hash
4. 查元数据:id=42 上次 hash != 当前 → 文本变了,继续
5. 删除 doc_vectors 中 source_pk=42 的所有 chunk
6. 切分新文本 → N 个 chunk → 批量 embedding
7. 生成确定性 vector_id → upsert N 条到 pgvector,更新 text_hash
8. RAG /search 立即能搜到新内容,旧内容不再命中
```

**DELETE 一行:** Debezium op=d → Worker 按 `source_pk` 删除全部 chunk → 立即从检索结果消失。

---

## 5. 一致性、容错与运维

| 关注点 | 方案 |
|---|---|
| 投递语义 | 至少一次 + 确定性 ID upsert = 最终幂等 |
| 顺序 | Kafka key=主键,同行变更同分区有序 |
| 失败重试 | 处理异常 → 退避重试 → 仍失败入 DLQ,人工 / 定时重投 |
| Embedding 限流 | 批量 + 令牌桶限速,避免打爆 API;snapshot 全量阶段尤其注意 |
| WAL 膨胀 | 监控 replication slot lag,Worker 长时间挂掉要告警(否则 PG 磁盘爆) |
| 重建索引 | 换 embedding 模型 / 切分策略变更 → 触发全量重放(Debezium re-snapshot) |
| 可观测 | 同步延迟(CDC→向量)、embedding 调用量/命中跳过率、DLQ 积压 |

---

## 6. 技术栈选型

| 环节 | MVP 选型 | 进阶 |
|---|---|---|
| CDC | Debezium standalone(Kafka Connect) | Debezium 集群 |
| 队列 | Kafka | — |
| Sync Worker | **Python**(AI 生态顺手)或 Java(贴合既有栈) | — |
| Embedding | 开源 bge/e5 本地起 或 Voyage API | 自托管 GPU 推理 |
| 向量库 | pgvector | Qdrant |
| RAG 服务 | Python(FastAPI) | — |
| 生成模型 | Claude Sonnet 4.6(性价比)/ Opus 4.8(复杂) | — |
| rerank | 暂无 | rerank 模型 |

> **唯一待你拍板的决策**:Sync Worker + RAG 服务用 **Python** 还是 **Java**?
> - 选 **Python**:AI / embedding / 向量库 SDK 生态最顺,贴合「学新技术」目标 —— **推荐**。
> - 选 **Java**:复用你现有技术栈与工程规范,但 AI 库生态不如 Python。
> 本文档默认按 Python 写示例,确认后我据此搭脚手架。

---

## 7. 分阶段路线图

**阶段 0 · 最小闭环(第 1 周)**
- Debezium 监听 PG 一张表 → Kafka
- Sync Worker 只处理 INSERT:抽文本 → embedding → 写 pgvector
- `/search` 接口能语义搜出来
- 目标:**端到端跑通一条数据**

**阶段 1 · 完整 CDC 语义(第 2 周)**
- 支持 UPDATE(删旧+写新)/ DELETE
- 确定性 ID + text_hash 去重
- DLQ + 重试

**阶段 2 · RAG 质量(第 3 周)**
- `/ask` 接 Claude 生成,带引用
- 加 rerank
- 多租 payload 过滤

**阶段 3 · 进阶(按需)**
- 切 Qdrant,学 HNSW 调优
- 跨表文档:先反查,压力大再评估 Flink CDC
- 监控大盘(同步延迟 / 跳过率 / DLQ)

---

## 8. 待决问题(Open Questions)

1. **语言**:Sync Worker / RAG 用 Python(推荐)还是 Java?→ 待确认
2. **Embedding 模型**:本地开源(省钱、练部署)还是托管 API(省事)?
3. **业务文档形态**:单表一行即文档,还是需要跨表拼?→ 决定是否给 Flink 留位置
4. **数据规模**:预估表行数 / 变更频率?→ 影响分区数、批大小、向量库选型时机

---

*下一步:确认第 6/8 节的待决问题后,即可生成项目脚手架(目录结构 + docker-compose + 最小可跑代码)。*
