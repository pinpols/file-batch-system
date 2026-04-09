package com.example.batch.e2e.config;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
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
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 勿用 mapper/**/*.xml：会重复加载多模块 XML 导致冲突；仅追加 business 子目录
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> mapperLocs = new ArrayList<>();
        for (Resource res : resolver.getResources("classpath*:mapper/*.xml")) {
            mapperLocs.add(res);
        }
        for (Resource res : resolver.getResources("classpath*:mapper/business/*.xml")) {
            mapperLocs.add(res);
        }
        factoryBean.setMapperLocations(mapperLocs.toArray(Resource[]::new));
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }
}
