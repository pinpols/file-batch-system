package com.example.batch.e2e.config;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * E2E merges orchestrator + several workers; each worker may register its own business
 * {@link SqlSessionFactory}. With multiple {@link DataSource} beans, MyBatis auto-config may not
 * register a primary {@code sqlSessionFactory} for {@code spring.datasource}, so orchestrator and
 * worker-core mappers would not resolve. This restores the platform (primary datasource) factory.
 */
@Configuration
public class E2ePlatformMybatisConfiguration {

    @Bean(name = "sqlSessionFactory")
    @Primary
    @ConditionalOnMissingBean(name = "sqlSessionFactory")
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }
}
