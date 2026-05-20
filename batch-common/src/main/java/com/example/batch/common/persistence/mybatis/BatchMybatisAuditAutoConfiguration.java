package com.example.batch.common.persistence.mybatis;

import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 注册 {@link AuditFieldsInterceptor} 到 MyBatis Configuration。各模块只要引 batch-common 即生效;关闭走配置 {@code
 * batch.mybatis.audit.enabled=false}。
 */
@AutoConfiguration
@ConditionalOnClass(SqlSessionFactory.class)
@ConditionalOnProperty(
    name = "batch.mybatis.audit.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BatchMybatisAuditAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public AuditFieldsInterceptor auditFieldsInterceptor(Clock batchClock) {
    return new AuditFieldsInterceptor(batchClock);
  }

  @Bean
  public ConfigurationCustomizer auditFieldsConfigurationCustomizer(
      AuditFieldsInterceptor interceptor) {
    return configuration -> configuration.addInterceptor(interceptor);
  }
}
