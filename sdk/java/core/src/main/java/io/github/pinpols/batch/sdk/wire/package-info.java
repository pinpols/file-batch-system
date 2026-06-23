/**
 * SDK 内部 —— worker ↔ 平台的有线协议 DTO(CLAIM / HEARTBEAT / REGISTER / RENEW / REPORT 请求体),由 {@code
 * client} / {@code internal} 组装收发。
 *
 * <p><b>非租户 API</b>:租户不应直接构造 / 依赖这些 DTO,字段随协议版本演进随时变更。
 */
package io.github.pinpols.batch.sdk.wire;
