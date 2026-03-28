package com.example.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class E2eImportWorkerDataSourceConfiguration {

    @Bean(name = "importPlatformDataSource")
    public DataSource importPlatformDataSource(@Qualifier("dataSource") DataSource dataSource) {
        return dataSource;
    }

    @Bean(name = "importBusinessDataSource")
    public DataSource importBusinessDataSource(@Qualifier("dataSource") DataSource dataSource) {
        return dataSource;
    }
}
