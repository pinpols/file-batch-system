package io.github.pinpols.batch.console.domain.rbac.web.response;

/**
 * 登录公钥响应（{@code GET /api/console/public-key}）。
 *
 * <p>历史实现返回 {@code Map.of("algorithm", "publicKey", "fingerprint")} 三个恒定字段，键一字不差。 由 controller
 * 边界直接从 {@code LoginKeyPairService} 的强类型来源构造，登录链路 wire 不变。
 */
public record ConsoleLoginPublicKeyResponse(
    String algorithm, String publicKey, String fingerprint) {}
