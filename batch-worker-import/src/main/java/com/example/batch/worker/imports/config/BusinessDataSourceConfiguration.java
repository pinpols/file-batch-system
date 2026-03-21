package com.example.batch.worker.imports.config;

import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.DataSourceBuilder;

@Configuration
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
public class BusinessDataSourceConfiguration {

    @Bean(name = "businessDataSource")
    public DataSource businessDataSource(BusinessDataSourceProperties properties) {
        return DataSourceBuilder.create()
                .url(properties.getUrl())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    @Bean(name = "businessJdbcTemplate")
    public JdbcTemplate businessJdbcTemplate(DataSource businessDataSource) {
        return new JdbcTemplate(businessDataSource);
    }
}
