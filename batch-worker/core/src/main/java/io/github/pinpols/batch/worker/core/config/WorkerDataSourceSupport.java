package io.github.pinpols.batch.worker.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.config.BatchPgSessionProperties;
import io.github.pinpols.batch.common.config.BusinessDataSourceBuilder;
import io.github.pinpols.batch.common.config.BusinessDataSourceProperties;
import io.github.pinpols.batch.common.config.BusinessRoutingProperties;
import io.github.pinpols.batch.common.config.HikariPgSessionSupport;
import io.github.pinpols.batch.common.mapper.BusinessTenantPlacementMapper;
import io.github.pinpols.batch.common.rls.RlsPolicyHealthIndicator;
import io.github.pinpols.batch.common.rls.RlsProperties;
import io.github.pinpols.batch.common.rls.RlsStartupFailFastCheck;
import io.github.pinpols.batch.common.tenant.routing.MyBatisTenantPlacementRepository;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * import/process/export 三个 worker 模块 {@code PlatformDataSourceConfiguration} / {@code
 * BusinessDataSourceConfiguration} 的公共装配逻辑 —— 原先三份文件里逐行相同的 HikariConfig 绑定、 DataSource 构建（含
 * pg-session 应用名回退）、SqlSessionFactory 装配、RLS 探针构造，均从这里搬来，未改动任何行为。
 *
 * <p>各模块 {@code @Bean} 方法名（如 {@code importBusinessHikariConfig}）与 {@code @ConfigurationProperties}
 * 绑定 prefix 保持不变——这些是被 {@code @Qualifier} 引用/外部配置文件依赖的公开契约， 不能内联到这个公共类里，因此各模块仍保留薄壳 {@code @Bean}
 * 方法，只是方法体委托到这里。
 */
public final class WorkerDataSourceSupport {

  private WorkerDataSourceSupport() {}

  /** 平台库 DataSource：把 {@link DataSourceProperties} 灌进 Hikari 配置 + 应用 pg-session 应用名回退。 */
  public static DataSource buildPlatformDataSource(
      DataSourceProperties properties,
      HikariConfig hikariConfig,
      BatchPgSessionProperties pgSessionProperties,
      Environment environment,
      String defaultAppName) {
    hikariConfig.setJdbcUrl(properties.determineUrl());
    hikariConfig.setUsername(properties.determineUsername());
    hikariConfig.setPassword(properties.determinePassword());
    String driverClassName = properties.determineDriverClassName();
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName(driverClassName);
    }
    String appName = environment.getProperty("spring.application.name", defaultAppName);
    HikariPgSessionSupport.applyPlatform(hikariConfig, pgSessionProperties, appName + "-platform");
    return new HikariDataSource(hikariConfig);
  }

  /** 平台库 SqlSessionFactory：mapper 位置固定为 {@code classpath*:mapper/*.xml}。 */
  public static SqlSessionFactory buildPlatformSqlSessionFactory(DataSource dataSource)
      throws Exception {
    return buildSqlSessionFactory(dataSource, "classpath*:mapper/*.xml");
  }

  /** 业务库 SqlSessionFactory：mapper 位置固定为 {@code classpath*:mapper/business/*.xml}。 */
  public static SqlSessionFactory buildBusinessSqlSessionFactory(DataSource dataSource)
      throws Exception {
    return buildSqlSessionFactory(dataSource, "classpath*:mapper/business/*.xml");
  }

  private static SqlSessionFactory buildSqlSessionFactory(
      DataSource dataSource, String mapperLocationPattern) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    Resource[] mappers =
        new PathMatchingResourcePatternResolver().getResources(mapperLocationPattern);
    if (mappers.length > 0) {
      factoryBean.setMapperLocations(mappers);
    }
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }

  /**
   * 业务库 DataSource：构造 + pg-session 回退 + 路由包裹统一收敛到 {@link BusinessDataSourceBuilder}；routing
   * 默认关=单片无损，开=多片。placement mapper 供 TABLE 模式 resolver 读 placement 表（单片/CONFIG 模式忽略）。
   */
  public static DataSource buildBusinessDataSource(
      HikariConfig hikariConfig,
      BusinessDataSourceProperties properties,
      BatchPgSessionProperties pgSessionProperties,
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      Environment environment,
      String defaultAppName) {
    String appName = environment.getProperty("spring.application.name", defaultAppName);
    return BusinessDataSourceBuilder.build(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        new MyBatisTenantPlacementRepository(placementMapper),
        appName);
  }

  /** RLS 闭世界健康探针构造，供 actuator/health 注册。 */
  public static RlsPolicyHealthIndicator buildRlsPolicyHealthIndicator(
      DataSource businessDataSource, RlsProperties rlsProperties) {
    return new RlsPolicyHealthIndicator(businessDataSource, rlsProperties.getExemptTables());
  }

  /** 启动期 RLS 闭世界 fail-fast 守门构造，复用 health indicator 同一套闭世界检查逻辑。 */
  public static RlsStartupFailFastCheck buildRlsStartupFailFastCheck(
      DataSource businessDataSource, RlsProperties rlsProperties) {
    return new RlsStartupFailFastCheck(businessDataSource, rlsProperties.getExemptTables());
  }

  /** {@link DataSourceTransactionManager} 构造——process 模块平台/业务两侧各有一个同构 TransactionManager bean。 */
  public static DataSourceTransactionManager buildTransactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }
}
