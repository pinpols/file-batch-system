# 数据库与 Java 注释审计（2026-08-21）

## 结论

当前数据库对象注释和 Java 架构边界类型注释均已完成本轮治理；后续数据库对象由增量门禁防止回退。

审计基线：本机 `batch_platform` 的 Flyway 历史已至 V193；V194 已以单事务直接执行完成 PostgreSQL
语法、对象存在性和目录注释验证，尚未写入本机 `flyway_schema_history`。表和字段统计均排除
`flyway_schema_history` 与继承月分区。

| 对象 | 总数 | 有注释 | 覆盖率 |
| --- | ---: | ---: | ---: |
| `batch` / `archive` 业务根表 | 123 | 123 | 100% |
| `batch` 业务关键字段 | 300 | 300 | 100% |
| `archive` 镜像字段 | 源表继承 | 源表已覆盖 | 不重复维护字段说明 |
| Java 应用入口、配置与自动配置类型 | 122 | 122 | 100% |

Java 生产源码共有 2,192 个顶层类型，其中 DTO、实体、Mapper、枚举、异常和单职责值对象由类型名及字段名表达语义，
不强制补充重复类注释。必须有架构注释的范围为应用入口、`@Configuration`、`@AutoConfiguration` 和
`@ConfigurationProperties`；本轮补齐了该范围的 31 个缺口，包括
`BatchOrchestratorApplication`、`BatchMqTopicsProperties` 和全部缺失的 Spring 配置类。

## 已完成治理

V194 已补齐业务根表职责注释缺口，并为 P0-P2 范围内的状态机、JSON 载荷、幂等键、策略枚举、
时间边界、密钥引用、哈希和版本字段补充语义注释，使 `batch` 业务关键字段达到 300/300 覆盖。典型覆盖对象包括：

- 调度主链：`job_definition`、`job_instance`、`job_partition`、`job_task`、`outbox_event`、`trigger_request`、`workflow_run`。
- 文件主链：`file_record`、`file_template_config`、`file_channel_config`、`file_dispatch_record`、`file_error_record`。
- 控制与租户域：`tenant`、`tenant_quota_policy`、`resource_queue`、`batch_window`、`business_calendar`、`api_key`、`secret_version`。
- 历史镜像：18 张 `archive.*_archive` 根表均已补充“源表镜像、写入方、保留目的”的表级说明。

技术主键、租户键和通用审计时点不重复写模板注释；其余 `batch` 关键字段由 V194 和新增迁移门禁覆盖。

## 治理标准

不要求为 `id`、`tenant_id`、`created_at`、`updated_at` 等全项目语义固定的技术列重复填写模板注释。下列对象必须有明确注释：

1. 每张业务根表：职责、写入方或权威来源、归档/保留语义。
2. 状态、策略、类型和模式字段：允许值的来源及状态机/决策含义。
3. JSON/文本载荷、外部引用、哈希、密钥引用和幂等键：格式、拥有者、敏感性和唯一性语义。
4. 时间、版本和计数列：时区/时点语义、CAS 或重试含义。
5. 归档表：源表、镜像方式和查询用途；字段级注释优先继承源表语义，避免复制漂移。

## 分批执行结果

| 批次 | 范围 | 验收 |
| --- | --- | --- |
| P0 | `job_*`、`outbox_event`、`trigger_*`、`workflow_*` | 已完成 |
| P1 | `file_*`、`pipeline_*`、`resource_*`、`tenant_*`、`batch_day_*` | 已完成 |
| P2 | 配置、安全、通知、审计、补偿和 `archive` 镜像表 | 已完成 |

V194 仅使用 `COMMENT ON TABLE` / `COMMENT ON COLUMN`；不改表名、列名、数据或索引。迁移需在空库和已升级库执行，并以 PostgreSQL 目录查询复核。本机验证为不写 Flyway 历史的单事务语法与对象检查；
正式环境仍必须由 Flyway 将 V194 记录为已执行版本。

## 已落地门禁

`scripts/ci/check-db-comment-coverage.sh` 已按上述“关键字段”集合校验本次新增或修改的迁移，不按所有列一刀切。门禁会：

- 拒绝新增业务根表却没有 `COMMENT ON TABLE`。
- 拒绝 `batch` 新增关键字段（状态、策略、载荷、幂等、密钥引用、时间边界）却没有 `COMMENT ON COLUMN`。
- 对月分区和归档镜像使用父表/源表继承规则，避免生成重复注释。

`scripts/ci/check-required-java-docs.sh` 在 PR 和 full gate 中校验应用入口及 Spring 配置类型的顶层
Javadoc，防止架构边界说明回退。

本次已在本机 `batch_platform` 事务执行 V194 并通过 PostgreSQL 目录复核：业务根表 `123/123`、
`batch` 关键字段 `300/300`。查询口径为排除继承月分区和 `flyway_schema_history`；表级通过
`obj_description`、字段级通过 `col_description` 判断。
