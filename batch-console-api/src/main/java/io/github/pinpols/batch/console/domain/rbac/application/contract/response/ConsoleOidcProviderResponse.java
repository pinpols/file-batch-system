package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

/** 登录页可见的 OIDC 登录入口；不暴露 issuer、client ID 或凭据。 */
public record ConsoleOidcProviderResponse(boolean enabled, String registrationId) {}
