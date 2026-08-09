# 错误码字典

> **自动生成** — 由 `scripts/codegen/gen-error-codes-dict.py` 从 `scripts/codegen/error-codes.yaml` 生成。**不要手动编辑此文件**；改错误码请改 YAML 源文件，重跑脚本。

共 17 条。HTTP 状态码遵循 RFC 7231。

| code | HTTP | 中文 label | 默认 message | 触发说明 |
|---|---|---|---|---|
| `SUCCESS` | 200 | 成功 | success | — |
| `INVALID_ARGUMENT` | 400 | 参数非法 | invalid argument | — |
| `VALIDATION_ERROR` | 400 | 参数校验失败 | validation failed | — |
| `MISSING_IDEMPOTENCY_KEY` | 400 | 缺少幂等键 | idempotency key is required | — |
| `NOT_FOUND` | 404 | 资源不存在 | resource not found | — |
| `CONFLICT` | 409 | 资源冲突 | resource conflict | — |
| `STATE_CONFLICT` | 409 | 状态冲突 | state conflict | — |
| `UNAUTHORIZED` | 401 | 未授权 | unauthorized | — |
| `FORBIDDEN` | 403 | 禁止访问 | forbidden | — |
| `RATE_LIMITED` | 429 | 请求过于频繁 | too many requests | — |
| `CAPTCHA_REQUIRED` | 401 | 需要人机验证 | captcha verification required | 登录风控:失败次数达阈值后要求人机验证(验证码),不锁账号——规避 account-lockout DoS。 FE 据该 code 弹验证码组件,过验证码后带 captchaToken 重试登录。 |
| `BUSINESS_ERROR` | 422 | 业务错误 | business error | — |
| `TENANT_SUSPENDED` | 422 | 租户已暂停 | tenant is suspended | R-arch-audit-2026-05-23 P1: 替代 QuartzLaunchJob 中 e.getMessage().contains("tenant is suspended") 字符串匹配。租户被运维 / 计费侧暂停后，调度路径需识别该语义并暂停对应 Quartz job 防止日志风暴。 |
| `NOT_IMPLEMENTED` | 501 | 未实现 | not implemented | — |
| `SERVICE_UNAVAILABLE` | 503 | 依赖组件暂不可用 | dependency temporarily unavailable | R-4.1 · 依赖组件短暂不可用（如 Redis 抖动）；表达"稍后重试安全"语义 |
| `CREDENTIAL_REF_UNRESOLVED` | 400 | 凭据引用无法解析 | credential reference unresolved | ADR-039 · 凭据 envRef(${ENV_NAME})在部署环境未定义,fail-fast 不静默回落明文 |
| `SYSTEM_ERROR` | 500 | 系统错误 | system error | — |

## 调用方建议处理

| HTTP 段 | 客户端建议 |
|---|---|
| 2xx | 正常处理 |
| 4xx (400/401/403/404/409/422/429) | 不要 retry，把 message 直接展示给用户或 PD |
| 5xx (500/501/503) | 可短暂 retry（指数退避），仍失败上报告警 |

特别注意：

- `RATE_LIMITED` (429) → 退避后重试，不要无限刷
- `SERVICE_UNAVAILABLE` (503) → 依赖组件抖动（如 Redis），稍后重试安全
- `STATE_CONFLICT` (409) → 状态机已推进，**不要**重试，重新查询状态
- `MISSING_IDEMPOTENCY_KEY` (400) → 写接口必须带 `Idempotency-Key` header

## 相关

- 源定义：[`scripts/codegen/error-codes.yaml`](../../scripts/codegen/error-codes.yaml)
- API 协议：[`../api/console-api-protocol.md`](../api/console-api-protocol.md) §错误码
- 编码规约：[`../coding-conventions.md`](../coding-conventions.md) §5 异常体系
