package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

/** Dispatch 官方渠道的安全能力标签。新增标签必须能在 adapter 实现里找到对应证据。 */
enum DispatchChannelSafetyAttribute {
  OFFICIAL_TYPE_ALLOWLIST,
  TIMEOUT_BOUND,
  SSRF_DNS_GUARD,
  PATH_SANITIZED,
  FILESYSTEM_SANDBOX,
  SIDECAR_MANIFEST,
  PAYLOAD_SIZE_BOUND,
  TLS_IDENTITY_CHECK,
  HOST_KEY_CHECK,
  HEADER_INJECTION_GUARD,
  CREDENTIAL_FROM_CHANNEL_CONFIG
}
