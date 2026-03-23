package com.example.batch.e2e.apps;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.e2e.apps.E2eDispatchApplication;
import com.example.batch.e2e.apps.E2eExportApplication;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import com.example.batch.e2e.config.E2eKafkaProducerConfiguration;
import com.example.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformMybatisConfiguration;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import com.example.batch.worker.imports.infrastructure.ImportStepExecutionAdapter;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAutoConfiguration
@EnableJdbcRepositories(basePackages = "com.example.batch.orchestrator.repository")
@Import({
    E2ePlatformDataSourceConfiguration.class,
    E2eKafkaProducerConfiguration.class,
    E2ePlatformMybatisConfiguration.class
})
@ComponentScan(
        basePackages = {
                "com.example.batch.e2e.support",
                "com.example.batch.orchestrator",
                "com.example.batch.worker.core",
                "com.example.batch.worker.exports"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchOrchestratorApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eImportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eExportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eDispatchApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerImportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerExportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerDispatchApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ImportStepExecutionAdapter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DispatchStepExecutionAdapter.class)
        }
)
@ImportAutoConfiguration({
        BatchJsonAutoConfiguration.class,
        BatchObjectCryptoAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(basePackages = "com.example.batch.orchestrator.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.example.batch.worker.core.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(E2eExportApplication.class, args);
    }
}
