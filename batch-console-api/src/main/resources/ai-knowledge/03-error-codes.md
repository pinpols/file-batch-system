# 错误码约定与常见错误码

## 约定
业务异常统一 `BizException.of(ResultCode.X, "error.<scope>.<reason>", args...)`。
错误码 key 为 snake_case,i18n 双语在 `messages.properties`(英文)+ `messages_zh_CN.properties`(中文)1:1 对齐。
HTTP 语义:认证/权限类落 401/403,参数/状态冲突类落 4xx,下游/存储异常不应直接抛出未映射异常成 500(应映射为合适的 4xx)。

## 常见错误码(scope = task / 任务执行)
- `error.task.already_claimed`:任务已被(其它/当前)worker 认领,状态非预期。并发 CLAIM 时出现。
- `error.task.lease_renew_rejected`:租约续期被拒(409)。通常是租约已过期或任务已被改派 —— worker 应重新 CLAIM 或放弃。

## 常见错误码(scope = common / 通用)
- `error.common.tenant_id_mismatch`:请求体 tenantId 与 header tenantId 不一致,被拒(防跨租户注入)。

## 常见错误码(scope = ai / 控制台助手)
- `error.ai.assistant_not_configured`:AI 已开启(enabled=true)但未配置可用的聊天模型(未注入 ANTHROPIC_API_KEY / OPENAI_API_KEY 或 provider 配错)。

## 排查错误码的方法
1. 在 `messages_zh_CN.properties` 搜该 key,看中文释义。
2. 全仓搜 `"error.<scope>.<reason>"` 字面量,定位抛出点。
3. 看抛出点上下文判断是参数问题、状态冲突还是下游异常。
