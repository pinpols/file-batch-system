# Realtime SSE Verification

本手册记录 `console-api` 的实时 SSE 验证流程，重点是 `ops-summary` 的 `summaryRefresh` 路径：

- SSE 首次连接返回 `ready`
- Redis Pub/Sub 收到 `summaryRefresh=true` 后，控制台会重新读取运维摘要
- SSE 最终收到 `ops-summary-updated`

## 前置条件

先确认这些服务已启动且健康：

- `batch-console-api`
- `batch-postgres`
- `batch-redis`

如果 `console-api` 是旧容器，先重建或重启它，确保包含最新的 `ops-summary` / tenant 解析修复。

## 订阅 `ops-summary` SSE

先打开一个 SSE 订阅窗口：

```bash
# 先 POST /api/console/auth/login 拿 JWT,把下面 $JWT 换成实际 token
# (X-Console-Token 共享密钥兼容路径已于 2026-04-30 物删,仅 JWT + SSE ticket 两条认证链)
curl -N --http1.1 -sS \
  -H "Authorization: Bearer $JWT" \
  -H 'X-Tenant-Id: default-tenant' \
  'http://localhost:18080/api/console/ops/summary/events?tenantId=default-tenant&initialSnapshot=false&heartbeatMillis=10000'
```

预期输出先出现：

```text
event:ready
```

如果后面没有触发摘要刷新，只会继续看到 `heartbeat`。

## 触发 `summaryRefresh`

再往 Redis 频道发布一条带 `summaryRefresh=true` 的消息：

```bash
docker exec batch-redis redis-cli PUBLISH 'batch:console:realtime' '{
  "originInstanceId":"external-test",
  "tenantId":"default-tenant",
  "stream":"ops-summary",
  "eventType":"ops-summary-triggered",
  "cursor":"cursor-ops-summary-verify",
  "summaryRefresh":true,
  "dataJson":"",
  "emittedAt":"2026-04-05T11:33:35Z"
}'
```

## 预期结果

SSE 窗口应继续输出：

```text
event:ops-summary-updated
```

完整数据会是一个新的运维摘要快照，字段包含：

- `pendingApprovals`
- `openAlerts`
- `criticalAlerts`
- `runningJobs`
- `failedJobs`
- `slaBreaches`
- `onlineWorkers`
- `drainingWorkers`
- `offlineWorkers`
- `outboxRetryBacklog`
- `outboxDeliveryFailures`

随后还会周期性看到：

```text
event:heartbeat
```

## 这条路径的含义

`summaryRefresh=true` 表示：

- Redis Pub/Sub 收到的是“刷新摘要”的触发信号
- 消费线程不会直接把触发事件原样透传给前端
- 控制台会重新读取数据库中的摘要并发出 `ops-summary-updated`

所以前端在这个流里，应该把 `ops-summary-updated` 当作最终的数据刷新事件。

## 常见异常

- 如果订阅一开始就出现 `reset-required`，通常说明你还在跑旧容器或旧代码。
- 如果 Redis 发布后没有看到 `ops-summary-updated`，先检查：
  - `batch-console-api` 日志里是否有 `console realtime redis pubsub consumer started`
  - `batch-redis` 是否可达
  - `batch-postgres` 是否健康

## 参考实现

- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/realtime/ConsoleOpsSummaryRealtimeStream.java`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/realtime/ConsoleRealtimeRedisPubSubConsumer.java`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleOpsApplicationService.java`
