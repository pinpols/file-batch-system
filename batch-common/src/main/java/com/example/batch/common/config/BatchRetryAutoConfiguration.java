package com.example.batch.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;

/**
 * 开启 Spring Retry（{@code @EnableRetry}），使各模块 {@link Retryable} 注解生效。
 *
 * <p>关闭方式：{@code batch.retry.enabled=false}（默认开启）。生产常备打开，本地联调
 * 如果想观察原始异常栈可暂关。
 *
 * <p>适用场景：数据库瞬时错误（死锁 / 序列化失败 / 连接抖动）。长期逻辑错误（约束冲突 / 业务校验失败）
 * 不应该走重试——@Retryable 的 retryFor 要显式列出瞬时异常类，不要 catch-all。
 */
@AutoConfiguration
@ConditionalOnClass(Retryable.class)
@ConditionalOnProperty(
    prefix = "batch.retry",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableRetry
public class BatchRetryAutoConfiguration {}
