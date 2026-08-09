# scripts/codegen

集中管理"声明式源 → 代码/文档"的生成器，避免手工维护多处副本导致漂移。

## gen-error-codes-dict.py

错误码的**单一事实源**是 [`error-codes.yaml`](error-codes.yaml)。

```bash
# 修改 error-codes.yaml 后重新生成：
python3 scripts/codegen/gen-error-codes-dict.py

# CI 校验生成物与 YAML 同步（pr-gate 已接入）：
python3 scripts/codegen/gen-error-codes-dict.py --check
```

生成产物：

- `batch-common/.../enums/ResultCode.java` —— 已加入 spotless 排除，生成器输出即最终格式
- `docs/dict/error-codes.md` —— 错误码字典文档

`--check` 同时校验每条 `detailKey` 已存在于 `messages.properties` 与
`messages_zh_CN.properties`，防止 i18n key 漂移。

依赖：Python 3.8+、PyYAML。
