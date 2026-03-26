package com.example.batch.worker.exports.config;

import com.example.batch.common.config.BusinessDataSourceProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration("exportWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@MapperScan(
        basePackages = "com.example.batch.worker.exports.mapper.business",
        sqlSessionFactoryRef = "exportBusinessSqlSessionFactory"
)
public class BusinessDataSourceConfiguration {

    @Bean(name = "exportBusinessHikariConfig")
    @ConfigurationProperties("batch.datasource.business.hikari")
    public HikariConfig exportBusinessHikariConfig() {
        return new HikariConfig();
    }

    @Bean(name = "exportBusinessDataSource")
    public DataSource exportBusinessDataSource(BusinessDataSourceProperties properties,
                                               @Qualifier("exportBusinessHikariConfig") HikariConfig hikariConfig) {
        hikariConfig.setJdbcUrl(properties.getUrl());
        hikariConfig.setUsername(properties.getUsername());
        hikariConfig.setPassword(properties.getPassword());
        if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
            hikariConfig.setDriverClassName("org.postgresql.Driver");
        }
        return new HikariDataSource(hikariConfig);
    }

    @Bean(name = "exportBusinessSqlSessionFactory")
    public SqlSessionFactory exportBusinessSqlSessionFactory(
            @Qualifier("exportBusinessDataSource") DataSource exportBusinessDataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(exportBusinessDataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/business/*.xml")
        );
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean(name = "exportBusinessSqlSessionTemplate")
    public SqlSessionTemplate exportBusinessSqlSessionTemplate(
            @Qualifier("exportBusinessSqlSessionFactory") SqlSessionFactory exportBusinessSqlSessionFactory) {
        return new SqlSessionTemplate(exportBusinessSqlSessionFactory);
    }
}
