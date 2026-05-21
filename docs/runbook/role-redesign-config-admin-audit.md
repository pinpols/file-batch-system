# V149 CONFIG_ADMIN→ADMIN 升级账号审计 runbook

> ADR-032 4 角色重设计落地时,V149 把所有 `ROLE_CONFIG_ADMIN` 一律升级为 `ROLE_ADMIN`(因历史上是全局信任角色)。**这是权限放大**,prod 上线后必须人工审计名单。

## 触发场景

- V149 刚跑过(`flyway_schema_history` 含 version=149)
- 距 V149 应用时间 < 30 天
- 上线后第一次 ops 例会

## Step 1:列出被升级的账号

```bash
PGPASSWORD=$PG_PASS psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d $PG_DB <<'SQL'
-- 所有现在持 ADMIN 但 V149 之前是 CONFIG_ADMIN 的账号:
-- updated_at = V149 应用时刻,且 authorities_csv 当前含 ADMIN
SELECT
    a.tenant_id,
    a.username,
    a.display_name,
    a.authorities_csv,
    a.enabled,
    a.updated_at AS upgraded_at,
    a.created_at,
    COALESCE(
        (SELECT MAX(installed_on)
         FROM batch.flyway_schema_history
         WHERE version = '149'),
        '1970-01-01'
    ) AS v149_applied_at
FROM batch.console_user_account a
WHERE a.authorities_csv LIKE '%ROLE_ADMIN%'
  AND a.updated_at = (
      SELECT MAX(installed_on)
      FROM batch.flyway_schema_history
      WHERE version = '149'
  )
ORDER BY a.tenant_id, a.username;
SQL
```

期望输出:每行一个被自动升级的账号。

## Step 2:对每条记录决策

逐条审视:

| 字段 | 判定 |
|---|---|
| `tenant_id = 'system'`,长期专职配置管理 | 升 ADMIN 合理,无须改 |
| `tenant_id != 'system'`,绑业务租户 | **可疑** — 历史 CONFIG_ADMIN 不应该绑业务租户;考虑降为 TENANT_ADMIN |
| `enabled = false`,长期未用 | 降为 TENANT_USER 或直接 disable;不该留 ADMIN |
| `display_name` 含「测试」「demo」「e2e」 | 降为 TENANT_USER 或删除 |
| 创建时间 > 1 年前,无近期登录 | 降级,有泄漏风险 |

## Step 3:批量降级(可选)

```sql
-- 示例 1:把绑业务租户的 CONFIG_ADMIN 升级账号统一降为 TENANT_ADMIN
UPDATE batch.console_user_account
SET authorities_csv = 'ROLE_TENANT_ADMIN',
    updated_at = CURRENT_TIMESTAMP
WHERE username IN ('user-a', 'user-b')  -- ← 填 Step 2 决策结果
  AND authorities_csv = 'ROLE_ADMIN';

-- 示例 2:直接 disable 长期未用的账号
UPDATE batch.console_user_account
SET enabled = false,
    updated_at = CURRENT_TIMESTAMP
WHERE username IN ('legacy-user-x', 'demo-y');
```

## Step 4:登录会话清理

降级 / disable 后,被改的账号当前 session 仍可能持旧 token(JWT 不主动失效)。**强制清掉**:

```bash
# Redis 清掉这些 username 的 session 注册
for u in user-a user-b legacy-user-x; do
  redis-cli -h $REDIS_HOST DEL "console:session:$u"
done
```

或调 BE 接口 `/api/console/admin/sessions/{username}/invalidate`(若有)。

## Step 5:存档审计结果

把 Step 1 输出 + Step 2 决策表写入运维 wiki(or 内部 ticket),并:

```bash
# 在 audit 表打一条手工 trail
psql <<SQL
INSERT INTO batch.console_operation_audit
    (operator, action, resource_type, payload, created_at)
VALUES
    ('ops-team', 'ROLE_AUDIT', 'console_user_account',
     '{"runbook":"role-redesign-config-admin-audit","reviewed":N,"downgraded":M}',
     CURRENT_TIMESTAMP);
SQL
```

填 N(总审账号数)/ M(降级数)。

## 频率

- **V149 上线 7 日内**:必做一轮
- 后续每季度对账一次(防止后台 ops 给账号加 ADMIN 不走流程)

## 关联 ADR

[ADR-032 4 角色 RBAC 重设计](../architecture/adr/ADR-032-four-role-rbac-redesign.md) §风险段
