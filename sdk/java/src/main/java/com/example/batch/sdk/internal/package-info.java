/**
 * SDK 内部实现 —— 平台 HTTP 通信底座({@link PlatformHttpClient} / {@link PlatformHttpException}),仅供 SDK
 * 自身({@code client} / {@code dispatcher})调用。
 *
 * <p><b>非租户 API</b>:租户(SDK 接入方)不应直接引用本包任何类型,接口随版本演进不保证兼容。租户的入口是 {@code
 * com.example.batch.sdk.client.BatchPlatformClient} 与 {@code com.example.batch.sdk.handler} /
 * {@code com.example.batch.sdk.task} 下的公开类型。
 */
package com.example.batch.sdk.internal;
