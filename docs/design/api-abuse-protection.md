# 接口防盗刷 / 防滥用设计

> 2026-06-24 立。来源：一次"前端加密 / 防 F12 / 防接口盗刷 / 防止租户拿 token 绕开前端直接调后端"的诉求澄清 + 现状审计。
>
> 配套：运维开关见 [`../runbook/feature-switches.md`](../runbook/feature-switches.md) §1.2（限流）/ §1.3（请求签名）；租户隔离见 [`./multi-tenant-and-security.md`](./multi-tenant-and-security.md)。

---

## 0. 核心认知（先纠偏，再谈方案）

这些是讨论的出发点，决定了为什么"前端加密 / 防 F12 / 让租户绕不开前端"不是防线：

1. **前端不是安全边界。** 前端代码、token、任何"签名密钥"最终都在用户浏览器里，能被看到、改写、复现。混淆 / 加密 / 反调试只是"抬高门槛"，不能当作安全保证。
2. **持有合法 token 的租户直接调后端，不是攻击，是 API 的设计本意。** 后端无法区分"来自官方前端"与"来自 curl 同一个 token"——没有任何密码学手段能做到。
3. 因此正确的问题不是"怎么拦住他绕开前端"，而是 **"他绕开前端能不能做成任何本来无权做的事？"**。只要答案是"不能"，绕不绕开前端都无所谓。
4. 真正的防线全在**服务端**：鉴权 + 租户隔离 + 字段白名单 + 业务校验 + 限流 + 防重放。让"直接调 API"与"走 UI"能力完全相等。

---

## 1. 威胁模型与职责边界

| 威胁 | 防御手段 | 归属 |
|---|---|---|
| 越权：调到别租户数据 / 高于自身角色的接口 | 服务端 `@PreAuthorize` + 全表 `tenant_id` + biz RLS + `InternalAuthFilter` 绑定 api_key→tenant | 已有 |
| 越权：curl 时多塞 `tenantId`/`role`/`status` 等字段冒充 | 服务端字段白名单接收、忽略客户端越权字段（如 `InternalRequestTenantGuard` 用解析出的 tenant 覆盖 body 声明） | 已有 / 持续核 |
| 越量：刷爆接口 / 超配额 / api_key 泄漏后被打爆 | 限流（按租户高水位）+ per-tenant quota | **P0（本轮）** |
| 重放 / 传输篡改（非浏览器脚本最现实） | 请求签名 HMAC + 时间戳窗口 + nonce 一次性 | **P1.4（本轮）** |
| 慢速 / 拟人滥用（低频绕过限流） | 行为风控打分（alert-only 起步） | P1.5（规划） |
| 暴力 / 爬虫 / CC | 验证码（风险触发）+ WAF / 网关 | P2（规划） |
| api_key 被盗后冒充 | TLS + 短期 key 轮换 + 限流（签名方案 A 不覆盖，见 §3.2） | 既有 + 运维 |

**不做**：前端代码加密 / 反调试 / "防 F12"——投入产出比低且可绕过，明确不作为安全手段（见 §0）。

---

## 2. 分层防御与落地状态

| 层 | 内容 | 状态 | PR |
|---|---|---|---|
| **P0.1** | orchestrator 限流默认开启：launch/release 3000、register 300 /min（高水位） | ✅ 已合 main | #708 |
| **P0.2** | claim/report 热路径按租户限流（12000/min），新增 `TASK_CLAIM`/`TASK_REPORT` | ✅ 已合 main | #708 |
| **P0.3** | console 昂贵接口（导出/导入/Excel/报表）端点级限流，按用户 10/min | ✅ 已合 main | #708 |
| **P1.4** | 后端 HMAC 验签 + ts + nonce 防重放（opt-in）+ Java SDK 签名跑通契约 | ✅ PR | #709 |
| **P1.4'** | 其余 4 语言 SDK（Go/TS/Python/Rust）签名照契约铺 | 规划 | — |
| **P1.5** | 行为风控 alert-only（频次突增 / 固定间隔 / 缺 UA-Referer）+ 短 token 评估 | 规划 | — |
| **P2** | 验证码（Turnstile/hCaptcha，风险触发）+ WAF / 网关集成点 | 规划 | — |

---

## 3. 关键设计决策

### 3.1 claim/report 按"租户"聚合限流，而非按 worker

`workerId` 是请求体里**可伪造的字符串**——被盗 api_key 可随意编造 workerId，按 worker 限流会被轮换 workerId 绕过。`tenantId` 由 `InternalAuthFilter` 绑定在 api_key 上、逃不掉，故按租户聚合。阈值 12000/min（=200/s）设在控制面合法峰值之上（单机 ~20 jobs/s、PG 有 10-15× 余量），只拦 runaway，可经 env 下调。批量端点（claim-batch/report-batch）按 HTTP 调用计 1。

### 3.2 请求签名采用"方案 A：以 api_key 为 HMAC 密钥"

| | 方案 A（采纳） | 方案 B（SigV4 式独立签名密钥） |
|---|---|---|
| 客户端发 | api_key + 签名头 | keyId + 签名头（密钥不上网） |
| 服务端验 | 请求头里已有 api_key，取来重算 HMAC | 按 keyId 查库取 signing_secret 重算 |
| 防重放 / 防篡改 | ✅ | ✅ |
| 防 api_key 被盗后冒充 | ❌（盗 key 也能签） | ⚠️ 部分 |
| schema / SDK 改动 | 零 schema；SDK 单密钥 | 加 `signing_secret` 列；SDK 双密钥 |

**取舍**：当前威胁主要是脚本重放 / 盗刷，ts+nonce 已堵住；方案 B 多出的"防 key 盗用"由 TLS + key 轮换 + P0 限流覆盖，不值当为它改 schema + 让 5 个 SDK 管两个密钥。需要时再升 B。

**契约**（服务端 `RequestSignatures` 与各语言 SDK 的唯一权威源，逐字节一致）：

```
canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
signature = hex(hmacSha256(apiKey, canonical))        // 小写 hex
头：X-Batch-Timestamp(epoch millis) / X-Batch-Nonce / X-Batch-Signature
```

校验顺序：缺头 → 时钟偏移（默认 ±300s）→ 签名 → nonce 一次性（Redis SETNX，TTL=2×窗口）。**签名先于 nonce**，避免未签 / 错签请求白白消耗 nonce 空间。跨语言一致性由 SDK 侧 conformance 测试钉死（SDK 实现与 `RequestSignatures` 对同一输入产出相同 hex）。

### 3.3 全部 opt-in、默认安全但不破坏存量

- 限流默认**开启**但阈值为高水位（不误伤）；签名默认**关闭**（避免存量 worker 被 401）。
- 签名灰度：先升级租户 SDK 并设 `BATCH_SDK_REQUEST_SIGNING_ENABLED=true`，确认全部带签名后再开服务端 `BATCH_REQUEST_SIGNING_ENABLED=true`。

---

## 4. 现状审计结论（2026-06-24，立项依据）

身份认证 / 幂等 / API Key 哈希（PBKDF2）/ 请求体上限**已很扎实**；防盗刷的真实缺口集中在"限流没全开 + 缺端点级粒度 + 无防重放 + 无行为风控"。本轮 P0+P1.4 即补齐前两项与防重放；行为风控 / 验证码 / WAF 留作 P1.5 / P2。
