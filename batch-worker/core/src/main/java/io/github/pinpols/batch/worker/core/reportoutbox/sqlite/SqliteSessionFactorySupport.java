package io.github.pinpols.batch.worker.core.reportoutbox.sqlite;

import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * ADR-015：SQLite report outbox 使用独立 {@link SqlSessionFactory}，与 Spring Boot 主 MyBatis 数据源隔离；
 * 装配逻辑供运行时配置与测试共用。
 */
public final class SqliteSessionFactorySupport {

  private SqliteSessionFactorySupport() {}

  public static SqlSessionFactory createSqlSessionFactory(DataSource dataSource) throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    Resource[] mapperResources = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:mapper/reportoutbox/sqlite/*.xml");
    factoryBean.setMapperLocations(mapperResources);
    Configuration configuration = new Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }
}
