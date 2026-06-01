/**
 * SDK 内部 —— 后台调度线程:心跳上报({@link HeartbeatScheduler})与租约续期({@link LeaseRenewalScheduler}),由 {@code
 * client} 统一启停。
 *
 * <p><b>非租户 API</b>:租户不应直接实例化 / 调度本包组件。
 */
package com.example.batch.sdk.scheduler;
