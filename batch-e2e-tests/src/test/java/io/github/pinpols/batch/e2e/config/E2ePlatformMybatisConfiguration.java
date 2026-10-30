package io.github.pinpols.batch.e2e.config;

import io.github.pinpols.batch.console.config.ConsoleAiUuidMappingConfiguration;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * E2E 上下文会合并 Orchestrator 和多个 Worker；每个 Worker 都可能注册自己的业务 {@link
 * SqlSessionFactory}. With multiple {@link DataSource} beans, MyBatis auto-config may not register
 * a primary {@code sqlSessionFactory} for {@code spring.datasource}, so orchestrator and
 * 否则 worker-core Mapper 将无法解析。此处恢复平台主数据源的 SqlSessionFactory。
 * Business mappers are deliberately excluded and use {@code e2eBusinessSqlSessionFactory}.
 */
@Component
@Import(ConsoleAiUuidMappingConfiguration.class)
public class E2ePlatformMybatisConfiguration {

  @Bean(name = "sqlSessionFactory")
  @Primary
  @ConditionalOnMissingBean(name = "sqlSessionFactory")
  public SqlSessionFactory sqlSessionFactory(
      @Qualifier("dataSource") DataSource dataSource,
      ObjectProvider<ConfigurationCustomizer> configurationCustomizers)
      throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    // 平台 factory 只加载平台 mapper，避免业务 SQL 被错误绑定到平台库。
    factoryBean.setMapperLocations(
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
    Configuration mybatisConfig = new Configuration();
    mybatisConfig.setMapUnderscoreToCamelCase(true);
    configurationCustomizers
        .orderedStream()
        .forEach(customizer -> customizer.customize(mybatisConfig));
    factoryBean.setConfiguration(mybatisConfig);
    return factoryBean.getObject();
  }
}
