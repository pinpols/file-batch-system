-- step_registry：各 worker / orchestrator 启动期把自身 Spring 上下文里注册的
-- Step / StageStep bean 名 + impl class 写入本表，console-api 的 Excel 上传
-- 校验 pipeline_step_definition.impl_code 时以此为白名单。
--
-- 快照覆盖策略：每次应用启动时先 DELETE WHERE module=? 再 INSERT 当前集合，
-- 保证已删除的 Step 类自动出表，避免冷数据。
CREATE TABLE IF NOT EXISTS batch.step_registry (
  id            BIGSERIAL PRIMARY KEY,
  module        VARCHAR(32)  NOT NULL,
  impl_code     VARCHAR(128) NOT NULL,
  impl_class    VARCHAR(256) NOT NULL,
  registered_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_step_registry_module_impl UNIQUE (module, impl_code),
  CONSTRAINT ck_step_registry_module CHECK (
    module IN ('IMPORT', 'EXPORT', 'DISPATCH', 'ORCHESTRATOR')
  )
);

CREATE INDEX IF NOT EXISTS idx_step_registry_module ON batch.step_registry (module);

COMMENT ON TABLE  batch.step_registry IS '应用启动期上报 Step bean 清单；Excel 配置上传按此白名单校验 impl_code';
COMMENT ON COLUMN batch.step_registry.module       IS '所属模块：IMPORT / EXPORT / DISPATCH / ORCHESTRATOR';
COMMENT ON COLUMN batch.step_registry.impl_code    IS 'Spring bean name，对应 pipeline_step_definition.impl_code';
COMMENT ON COLUMN batch.step_registry.impl_class   IS 'Step 实现类全限定名';
COMMENT ON COLUMN batch.step_registry.last_seen_at IS '最近一次应用启动登记的时间（快照策略下每次启动都会更新）';
