package io.github.pinpols.batch.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 单独装配 {@link BatchPgSessionProperties}：避免与 {@link BatchPgSessionAutoConfiguration} 中的 {@link
 * org.springframework.beans.factory.config.BeanPostProcessor} 落在同一 {@link AutoConfiguration}， 否则
 * Spring 在注册 BPP 时仍会过早初始化配置属性 bean，触发 {@code BeanPostProcessorChecker} WARN。
 */
@AutoConfiguration
@ConditionalOnClass(HikariDataSource.class)
@EnableConfigurationProperties(BatchPgSessionProperties.class)
public class BatchPgSessionPropertiesAutoConfiguration {}
