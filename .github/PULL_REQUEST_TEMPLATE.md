<!--
PR 描述模板 — 编辑时仅删除不适用段落,保留勾选/填写部分。
-->

## 概要

<!-- 1-3 句话:为什么要做、改了什么 -->

## 变更类型

- [ ] feat / fix / refactor / docs / chore / test / perf / build / ci

## 影响范围

<!-- 模块名:batch-common / batch-trigger / batch-orchestrator / batch-worker-{core,import,export,process,dispatch} / batch-console-api / batch-e2e-tests / docs -->

---

## 涉及"删除"语义的接口(若适用,逐项确认 — 详见 `docs/design/delete-strategy.md` §8)

- [ ] 是否有其他表以 FK 引用本表主键?**有则必须软删除**
- [ ] HTTP 方法是否正确:物理删除用 `DELETE`,软删除用 `PATCH`
- [ ] 软删除接口是否复用了 `EnabledPatchRequest` / `BatchEnabledPatchRequest`
- [ ] 对应 QueryRequest 的 `enabled` 字段是否设置了 `= true` 默认值
- [ ] OpenAPI YAML 和 `console-api-protocol.md` 是否同步更新

## 涉及 `batch-console-api` Controller 修改(若适用)

- [ ] `docs/api/console-api.openapi.yaml`(path / schema)同步更新,无悬空 `$ref`
- [ ] `docs/api/console-api-protocol.md` Changelog 表追加一行

## 涉及枚举字典

- [ ] 实现 `DictEnum`(`code()` / `label()`),Lombok 三连(`@RequiredArgsConstructor` + `@Accessors(fluent=true)` + `@Getter`)
- [ ] 暴露给前端:登记到 `ConsoleMetaQueryService.REGISTRATIONS` 且同步 OpenAPI `CommonResponseMetaEnums`;否则加入 `ConsoleMetaEnumRegistrationTest#EXCLUDED` 白名单注明原因

## 涉及方法参数 ≥ 6

- [ ] 公共接口:已封装为 Command / Context / Request / Param 对象
- [ ] 内联 `new` 调用 argc>6:已用 `@Builder` + 提取引用变量;参考 `docs/analysis/positional-args-cleanup-plan.md`

## 涉及业务异常

- [ ] 走 `BizException.of(ResultCode.XXX, "error.<scope>.<reason>", args...)`;**不**用旧 literal 构造器
- [ ] i18n key 双语对齐(`messages.properties` + `messages_zh_CN.properties` 1:1)

## 涉及规范条款本身变化

- [ ] CLAUDE.md 已同步追加/修改
- [ ] `docs/changelog.md` 已按日期倒序追加条目

---

## 验证

<!-- 跑了什么测试、Smoke/IT/E2E 结果、数据校验等 -->

- [ ] `mvn -DskipTests package` 全模块成功
- [ ] 关联模块单测/IT 全绿(列出关键 test 名称)
- [ ] 涉及 schema/migration:本地 PG 已 ALTER + flyway_schema_history 同步,回滚 SQL 备好

## 其他

<!-- 灰度计划 / 回滚预案 / runbook 链接 -->
