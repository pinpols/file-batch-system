package io.github.pinpols.batch.console.domain.rbac.web.response;

/**
 * 验证码公开配置响应（{@code GET /api/console/captcha/config}）。
 *
 * <p>历史实现返回 {@code Map.of("provider", "siteKey", "loginProtectionEnabled")} 三个恒定字段。 <b>绝不下发
 * secretKey</b>；键与历史 wire 一字不差。
 */
public record ConsoleCaptchaConfigResponse(
    String provider, String siteKey, boolean loginProtectionEnabled) {}
