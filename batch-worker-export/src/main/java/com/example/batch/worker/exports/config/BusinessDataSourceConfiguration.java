package com.example.batch.worker.exports.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties({
        BusinessDataSourceProperties.class,
        MinioStorageProperties.class,
        ExportWorkerConfiguration.class
})
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
    public JdbcTemplate businessJdbcTemplate(@Qualifier("businessDataSource") DataSource businessDataSource) {
        return new JdbcTemplate(businessDataSource);
    }
}
