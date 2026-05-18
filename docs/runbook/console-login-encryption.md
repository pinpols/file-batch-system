# Console 登录请求体加密 — 运维与密钥轮换

> 2026-05-18 落地。BE: `ConsoleLoginKeyPairService` + `/api/console/auth/public-key`。FE: `src/utils/loginCrypto.ts` + `authApi.login`。

## 1. 设计回顾

FE 用浏览器 Web Crypto 把 `{username, password}` 加密成 `{encryptedKey, iv, ciphertext}` 三字段 base64 发到 `POST /api/console/auth/login`：

- **RSA-OAEP-SHA256** 包装一次性 AES-256 key（32 字节）→ `encryptedKey`
- **AES-256-GCM** 加密 body JSON → `ciphertext` + 12 字节 `iv`
- BE 拿 RSA 私钥解 AES key → AES-GCM 解 body → 走原 `ConsoleLoginService.login()`

## 2. 配置模式

| profile | `enabled` | `required` | PEM | 行为 |
|---|---|---|---|---|
| local / dev / test | `true` | `false`（默认）| 留空 | 启动期生成内存密钥；BE 同时接受加密 + 明文；e2e 不破 |
| prod / staging / uat | `true`（强制）| `true`（强制）| **必填** | 仅接受加密；明文 → `401 error.auth.encryption_required` |

PostConstruct 守护（`ConsoleSecurityProperties.validateLoginEncryptionInProdProfile`）：prod-like profile 下置 `enabled=false` 或 `required=false` 启动 fail-fast。

helm Secret 模板守护（`helm/batch-platform/templates/secret.yaml`）：`required=true` 时 PEM 留空 → `helm template` 直接失败，**不可能 deploy 到生产**。

## 3. multi-replica 必须显式注入 keypair（重要）

启动期生成密钥的路径**只适合单副本**。多副本场景下：

```
Pod A 自己生成 keypair (priv_A, pub_A)
Pod B 自己生成 keypair (priv_B, pub_B)
FE -> GET /auth/public-key  → 命中 Pod A 返 pub_A
FE -> POST /auth/login (用 pub_A 加密) → 命中 Pod B → 用 priv_B 解 → 失败
```

`helm/values-prod.yaml` 已强制 `required=true`，PEM 必填，helm 守卫阻止漏配。

## 4. 首次部署（密钥生成）

```bash
# 1. 运维主机一次性生成
openssl genrsa -out console-login.priv.pem 2048
openssl rsa -in console-login.priv.pem -pubout -out console-login.pub.pem

# 2. 校验密钥对自洽
echo "ping" | openssl pkeyutl -encrypt -pubin -inkey console-login.pub.pem \
  -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 | \
  openssl pkeyutl -decrypt -inkey console-login.priv.pem \
  -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256
# 期望输出: ping

# 3. helm deploy（推荐走 Vault / sealed-secrets / KMS,不要 commit 进 git）
helm upgrade batch-platform helm/batch-platform \
  -f helm/values-prod.yaml \
  --set security.internalSecret="${INTERNAL_SECRET}" \
  --set security.consoleJwtSecret="${CONSOLE_JWT_SECRET}" \
  --set-file security.loginEncryption.privateKeyPem=console-login.priv.pem \
  --set-file security.loginEncryption.publicKeyPem=console-login.pub.pem

# 4. 验证 endpoint 健康
kubectl exec -n batch deploy/console-api -- \
  curl -s http://localhost:18080/api/console/auth/public-key | jq .data
# 期望返回 { algorithm: "RSA-OAEP-256", publicKey: "-----BEGIN PUBLIC KEY-----...", fingerprint: "<8-hex>" }
```

## 5. 密钥轮换流程（推荐每 6-12 个月一次）

### 5.1 准备阶段（不影响线上）

```bash
# 生成新密钥对
openssl genrsa -out console-login.priv.NEW.pem 2048
openssl rsa -in console-login.priv.NEW.pem -pubout -out console-login.pub.NEW.pem
```

### 5.2 切换（5-10 分钟窗口）

1. **helm upgrade 注入新 PEM** —— rollingUpdate 让 console-api pod 滚动替换
2. **观测期 5 分钟**：
   - Prometheus 查看 `4xx_total{path="/api/console/auth/login"}` 是否飙升
   - 日志 grep `error.auth.encryption_failed` 出现频率
3. FE sessionStorage 缓存的旧公钥 5 分钟自动过期，下次登录刷到新公钥；存量用户在缓存窗口内的登录可能瞬时 401，FE 报错后刷新页面即可

### 5.3 验证

```bash
# 取一次新公钥指纹
kubectl exec deploy/console-api -- \
  curl -s http://localhost:18080/api/console/auth/public-key | jq -r .data.fingerprint

# 与新密钥指纹比对
openssl rsa -in console-login.priv.NEW.pem -pubout -outform DER 2>/dev/null | \
  openssl dgst -sha256 -binary | head -c 8 | xxd -p
# 两个值应一致
```

### 5.4 旧密钥废弃

旧 `.pem` 从密钥管理系统删除（Vault revoke / KMS disable）。**不需要保留** —— RSA 私钥不参与历史数据解密，只服务于实时登录请求。

## 6. 故障排查

| 症状 | 原因 | 解法 |
|---|---|---|
| 登录 401 `error.auth.encryption_required` | FE 把明文发到 prod（required=true）| 检查 FE 是否能成功 GET `/auth/public-key`（network 抓包）；公钥取不到 → FE 回退明文路径会被 BE 拒 |
| 登录 401 `error.auth.encryption_failed` | 公钥/私钥不匹配 | helm template 是否把同一对 PEM 注入；multi-replica 检查所有 pod 的 fingerprint 一致：<br>`kubectl exec <each-pod> -- curl /auth/public-key \| jq .data.fingerprint` |
| 启动 `IllegalStateException: login-encryption.required=false 在 prod-like profile 下被禁止` | prod profile 配错了 | 走 helm 注入，不要 `--set required=false` 跑 prod |
| helm template `privateKeyPem 必须提供 (required=true 时)` | 缺密钥 | 按 §4 注入 PEM |
| 不同 pod fingerprint 不一致 | 单副本配置跑了多副本 | 立即按 §4 注入显式 keypair，等 rolling update 完成 |

## 7. 关闭加密功能（应急逃生）

**不建议**关 —— prod 守护拒绝把 `required=false`/`enabled=false`。

真要紧急逃生：
- 走总开关 `batch.security.bypass-mode=true`（整条安全链短路，仅本地/联调）
- 不要尝试把 `login-encryption.enabled=false` 单独关 —— 守护拒绝

## 8. 与 e2e / 自动化测试的关系

- **本地 dev / docker / CI**：`required=false`（默认）—— BE 接受明文 + 加密双形态；Playwright UI e2e 自动走加密；`api-full-coverage.sh` 明文路径继续可用，**零改动**
- **staging API direct e2e**（如果有）：`required=true` → 需要 shell 加密助手（`openssl rsautl` + `openssl enc -aes-256-gcm`），约 30 行；目前不强求

## 9. 相关源

- BE 密钥服务：[`ConsoleLoginKeyPairService.java`](../../batch-console-api/src/main/java/com/example/batch/console/support/auth/ConsoleLoginKeyPairService.java)
- BE controller：[`ConsoleAuthController.java`](../../batch-console-api/src/main/java/com/example/batch/console/web/ConsoleAuthController.java)
- BE 配置 + 守护：[`ConsoleSecurityProperties.java`](../../batch-console-api/src/main/java/com/example/batch/console/config/ConsoleSecurityProperties.java)
- FE 加密：[`loginCrypto.ts`](../../../batch-console/src/utils/loginCrypto.ts)
- Helm Secret：[`helm/batch-platform/templates/secret.yaml`](../../helm/batch-platform/templates/secret.yaml)
- i18n key：`error.auth.encryption_required` / `_failed` / `_unavailable`
