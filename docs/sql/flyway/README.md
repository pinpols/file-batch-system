# Flyway 迁移脚本索引

## 目录职责

本目录只放 `batch_platform` 的 PostgreSQL 迁移脚本。

- `batch` schema：平台业务表、配置表、运行表、文件表、审计表、补偿表
- `quartz` schema：只保留 Quartz 官方 JobStore 元数据边界，不在这里手写 `QRTZ_*` 表

## 命名规则

- `V<number>__<desc>.sql`：按版本顺序执行的迁移脚本
- 同一版本只做一类变更，避免把 schema、索引、数据和治理逻辑混在一个文件里
- 文件名只表达“做什么”，详细背景和字段口径写在文件头注释里

## 阅读顺序

- `V1` - `V7`：平台 schema、基础配置、定义、运行、文件、运维表和初始索引
- `V8` - `V10`：文件流程扩展、错误记录、AI 审计
- `V11` - `V15`：SLA、补偿运行态、导入预处理、调度公平性快照
- `V16` - `V19`：补偿去重、模板安全、告警、worker drain
- `V20` - `V23`：outbox、配置发布、通道扩展、运行默认参数目录
- `V24` - `V32`：配额运行态、通道健康、审批命令、node 类型、插件引用、ShedLock、批量日、乐观锁版本

## 使用说明

1. 迁移执行顺序以 Flyway 版本号为准，不要手工跳号。
2. 新增字段优先写在对应业务表的迁移脚本里，必要时再补索引和约束。
3. 业务语义变更要同时更新这里的文件头注释和 `docs/architecture/runtime-default-parameters.md` 等配套文档。
4. Quartz 表初始化继续使用官方 PostgreSQL JobStore 脚本，不在本目录内复制 `QRTZ_*` DDL。

## 关联文档

- [sql 根目录索引](../README.md)
- [运行默认参数说明](../../architecture/runtime-default-parameters.md)
