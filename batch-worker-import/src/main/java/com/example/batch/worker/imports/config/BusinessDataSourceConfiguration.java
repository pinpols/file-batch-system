package com.example.batch.worker.imports.config;

import com.example.batch.common.config.BusinessDataSourceProperties;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration("importWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@MapperScan(
        basePackages = "com.example.batch.worker.imports.mapper.business",
        sqlSessionFactoryRef = "importBusinessSqlSessionFactory"
)
public class BusinessDataSourceConfiguration {

    @Bean(name = "importBusinessDataSource")
    public DataSource importBusinessDataSource(BusinessDataSourceProperties properties) {
        return DataSourceBuilder.create()
                .url(properties.getUrl())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    @Bean(name = "importBusinessSqlSessionFactory")
    public SqlSessionFactory importBusinessSqlSessionFactory(
            @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(importBusinessDataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/business/*.xml")
        );
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean(name = "importBusinessSqlSessionTemplate")
    public SqlSessionTemplate importBusinessSqlSessionTemplate(
            @Qualifier("importBusinessSqlSessionFactory") SqlSessionFactory importBusinessSqlSessionFactory) {
        return new SqlSessionTemplate(importBusinessSqlSessionFactory);
    }
}
