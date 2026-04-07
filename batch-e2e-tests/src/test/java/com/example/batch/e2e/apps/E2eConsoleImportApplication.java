package com.example.batch.e2e.apps;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.e2e.apps.E2eDispatchApplication;
import com.example.batch.e2e.apps.E2eExportApplication;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.config.E2eImportWorkerDataSourceConfiguration;
import com.example.batch.e2e.config.E2eKafkaProducerConfiguration;
import com.example.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformMybatisConfiguration;
import com.example.batch.e2e.config.E2eShedLockConfiguration;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import com.example.batch.worker.exports.infrastructure.ExportStepExecutionAdapter;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAutoConfiguration(exclude = {
        com.example.batch.common.logging.HttpRequestMdcAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class
})
@EnableKafka
@EnableJdbcRepositories(basePackages = {
        "com.example.batch.orchestrator.repository",
        "com.example.batch.console.repository"
})
@Import({
        E2ePlatformDataSourceConfiguration.class,
        E2eKafkaProducerConfiguration.class,
        E2eImportWorkerDataSourceConfiguration.class,
        E2ePlatformMybatisConfiguration.class,
        E2eShedLockConfiguration.class
})
@ComponentScan(
        basePackages = {
                "com.example.batch.e2e.support",
                "com.example.batch.console.application",
                "com.example.batch.console.config",
                "com.example.batch.console.domain",
                "com.example.batch.console.infrastructure",
                "com.example.batch.console.support",
                "com.example.batch.console.service",
                "com.example.batch.console.web",
                "com.example.batch.orchestrator",
                "com.example.batch.worker.core",
                "com.example.batch.worker.imports"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchConsoleApiApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchOrchestratorApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eImportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eExportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = E2eDispatchApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.example.batch.orchestrator.config.ShedLockConfiguration.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerImportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerExportApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BatchWorkerDispatchApplication.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ExportStepExecutionAdapter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DispatchStepExecutionAdapter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.example.batch.worker.imports.config.PlatformDataSourceConfiguration.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.example.batch.worker.imports.config.BusinessDataSourceConfiguration.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.example.batch.worker.imports.config.ShedLockConfiguration.class)
        }
)
@ImportAutoConfiguration({
        BatchJsonAutoConfiguration.class,
        BatchObjectCryptoAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(
        basePackages = "com.example.batch.console.mapper",
        sqlSessionFactoryRef = "sqlSessionFactory",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class
)
@MapperScan(basePackages = "com.example.batch.orchestrator.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.example.batch.worker.core.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eConsoleImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(E2eConsoleImportApplication.class, args);
    }
}
