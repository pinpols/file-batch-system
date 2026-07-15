-- =========================================================
-- biz.* 只读排故角色(least privilege)。补 rls-phase-a 缺的「排查故障用哪个账户」。
--
-- 部署位置:每个 biz 分片的 batch_business 库(同 rls-phase-a.sql)。
--   psql -d batch_business -f scripts/db/business/diagnostic-readonly-role.sql
--
-- 账户模型(每片 PG 内,按用途拆角色,least privilege):
--   batch_business_writer        — 应用 R/W,RLS 生效(worker 路由数据源用)。见 rls-phase-a.sql。
--   batch_business_admin         — BYPASSRLS,跨租聚合/forensic export(平台用,审计)。见 rls-phase-a.sql。
--   batch_business_readonly      — 【本脚本】RLS 生效的「租户内只读」。日常租户范围排故,
--                                   连上后须 SET LOCAL app.tenant_id 才看得到该租户行;防误写(无 DML)。
--   batch_business_readonly_all  — 【本脚本】BYPASSRLS 的「跨租户只读」。跨租对账/排故,
--                                   能看全部租户但只读;权限最敏感,须审计 + 限人。
--
-- 排故选哪个:
--   · 单租户问题 → batch_business_readonly(最小权限,RLS 回退防越权看别家)。
--   · 跨租户/对账/平台级排查 → batch_business_readonly_all(只读 BYPASSRLS,审计)。
--   · 绝不要用 batch_business_writer 排故(会误写),更不要用 superuser(RLS 对它豁免=隔离失效)。
-- =========================================================

-- 密码必须由调用方显式注入,禁用已知默认密码(readonly_all 是 BYPASSRLS,尤须显式密码)。
--   调用方须传:  psql -v readonly_password=... -v readonly_all_password=...  (建议 -v ON_ERROR_STOP=1)
--   未传 / 为空 → RAISE EXCEPTION 失败退出,绝不退回弱默认密码。
--   prod 由运维 / secret 后端注入;本地 / sim 由 provision-biz-shard.sh / sim-harness.sh 传本地值。

-- RLS 生效的租户内只读。
\if :{?readonly_password}
  SELECT (:'readonly_password' = '') AS _ro_pw_empty \gset
  \if :_ro_pw_empty
    DO $$ BEGIN RAISE EXCEPTION 'readonly_password 为空 — 拒绝创建弱密码角色 batch_business_readonly'; END $$;
  \else
    SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_business_readonly') AS _mk_ro \gset
    \if :_mk_ro
      CREATE ROLE batch_business_readonly LOGIN PASSWORD :'readonly_password' NOBYPASSRLS;
      COMMENT ON ROLE batch_business_readonly IS
        'biz.* read-only, RLS enforced (per-tenant diagnostics). SELECT only.';
    \endif
  \endif
\else
  DO $$ BEGIN RAISE EXCEPTION 'readonly_password 未注入 — 拒绝用默认密码创建 batch_business_readonly(psql -v readonly_password=...)'; END $$;
\endif

-- BYPASSRLS 的跨租户只读(只读,审计)。BYPASSRLS = 看全部租户,密码务必显式注入。
\if :{?readonly_all_password}
  SELECT (:'readonly_all_password' = '') AS _roa_pw_empty \gset
  \if :_roa_pw_empty
    DO $$ BEGIN RAISE EXCEPTION 'readonly_all_password 为空 — 拒绝创建弱密码 BYPASSRLS 角色 batch_business_readonly_all'; END $$;
  \else
    SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_business_readonly_all') AS _mk_roa \gset
    \if :_mk_roa
      CREATE ROLE batch_business_readonly_all LOGIN PASSWORD :'readonly_all_password' BYPASSRLS;
      COMMENT ON ROLE batch_business_readonly_all IS
        'biz.* cross-tenant read-only, BYPASSRLS (audited diagnostics). SELECT only.';
    \endif
  \endif
\else
  DO $$ BEGIN RAISE EXCEPTION 'readonly_all_password 未注入 — 拒绝用默认密码创建 BYPASSRLS 角色 batch_business_readonly_all(psql -v readonly_all_password=...)'; END $$;
\endif

GRANT USAGE ON SCHEMA biz TO batch_business_readonly, batch_business_readonly_all;
GRANT SELECT ON ALL TABLES IN SCHEMA biz
  TO batch_business_readonly, batch_business_readonly_all;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA biz
  TO batch_business_readonly, batch_business_readonly_all;
ALTER DEFAULT PRIVILEGES IN SCHEMA biz
  GRANT SELECT ON TABLES
  TO batch_business_readonly, batch_business_readonly_all;
ALTER DEFAULT PRIVILEGES IN SCHEMA biz
  GRANT SELECT ON SEQUENCES
  TO batch_business_readonly, batch_business_readonly_all;
