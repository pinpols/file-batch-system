package io.github.pinpols.batch.worker.processes.cleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * P1-A3：staging 清理相关 {@code @ConfigurationProperties} 注册的独立配置类。
 *
 * <p>之前直接在 {@link ProcessStagingOrphanCleaner} 上同时挂 {@code @Component} 与 {@code @Configuration}，让
 * Spring 对一个只做调度的 component 多做一份 CGLIB 代理；拆出本类后 cleaner 退回纯 {@code @Component}， 启动期减少一次代理生成，且
 * {@code @EnableConfigurationProperties} 也回到惯例位置（独立 Configuration）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessStagingCleanupProperties.class)
public class ProcessStagingCleanupAutoConfiguration {}
