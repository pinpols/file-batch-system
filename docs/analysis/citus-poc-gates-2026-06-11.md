# Citus 两道生死门 POC 实测(2026-06-11,本地 3 节点集群)

> 环境:citusdata/citus:13.0(PG17),1 coordinator + 2 worker,docker network。
> 结论:**两关全过,Citus 路径打通**。citus-introduction-plan §0.5 的两个阻塞 POC 完成。

## 门 1:RLS 透传 coordinator → worker(§7)— ✅ 条件通过

| 测试 | 结果 |
|---|---|
| 默认配置 `SET LOCAL app.tenant_id` | ❌ 不透传 → 读全空(危险静默,但不泄漏) |
| `citus.propagate_set_commands='local'`(ALTER SYSTEM 集群固化) | ✅ 完美透传,只见本租户 |
| 跨租户强查(ta 身份查 tb) | ✅ 0 行 |
| 写入:本租户 INSERT | ✅ 成功 |
| 写入:越权 INSERT(ta 身份写 tb 行) | ✅ worker 上 RLS CHECK 拒绝 |
| 漏配 GUC 时的安全性质 | 读=全空、写=全拒(fail-fast,不泄漏不写错) |

**生产要求**:coordinator `ALTER SYSTEM SET citus.propagate_set_commands='local'` 必须进部署 checklist + 启动期自检(SHOW 校验,漏配 fail-fast)。

## 门 2:useGeneratedKeys 档B(同事务 RETURNING id → 子表 FK)— ✅ 通过

- distributed 表 `INSERT ... RETURNING id`(BIGSERIAL):coordinator 统一分配,跨租户全局唯一(实测 1/2/3)
- 同事务用回读 id 写 colocated 子表:✅
- colocated FK `(tenant_id, parent_id) → (tenant_id, id)`:可建、强校验 ✅
- → 台账 9 处档B 模式全部可行;档C(outbox→event_outbox_retry 跨事务持 id)同理可行(id 全局唯一)

## 重大简化发现(修正 14-24 周估算的输入)

54 处"其他表 on conflict"逐类复查:绝大多数 conflict target 是 `(tenant_id, ...)` 业务唯一键——
**已含分片键,Citus distributed 化后语法语义原样成立,无需改造**。真正要动的只有:
- conflict target = 单列 `(id)` 或不含 tenant_id 的(逐处清点进 Phase 2 worklist)
- reference 表(配置小表)上的 on conflict:reference 表全分片复制,约束不变,**也无需改**

→ ON CONFLICT 实际改造面远小于"56 处全过一遍"的悲观假设。
