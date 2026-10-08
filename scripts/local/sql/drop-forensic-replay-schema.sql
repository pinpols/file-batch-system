-- DANGER: 删除取证回放 schema 及其全部对象。仅限本地回放库，先确认 schema 名称和备份状态。
DROP SCHEMA IF EXISTS :"replay_schema" CASCADE;
