package com.example.batch.e2e.apps;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.e2e.apps.E2eDispatchApplication;
import com.example.batch.e2e.apps.E2eExportApplication;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import com.example.batch.e2e.config.E2eExportWorkerDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformMybatisConfiguration;
import com.example.batch.e2e.config.E2eShedLockConfiguration;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import com.example.batch.worker.imports.infrastructure.ImportStepExecutionAdapter;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
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
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration.class
})
@EnableKafka
@EnableJdbcRepositories(basePackages = "com.example.batch.orchestrator.repository")
@Import({
    E2ePlatformDataSourceConfiguration.class,
    E2eExportWorkerDataSourceConfiguration.class,
    E2ePlatformMybatisConfiguration.class,
    E2eShedLockConfiguration.class
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
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.example.batch.orchestrator.config.ShedLockConfiguration.class),
                // REGEX 按全限定类名匹配，避免 ASSIGNABLE_TYPE 在注解解析期加载 worker-export 配置类（不完整 jar 会 CNFE）
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.batch\\.worker\\.exports\\.config\\.PlatformDataSourceConfiguration"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.batch\\.worker\\.exports\\.config\\.BusinessDataSourceConfiguration"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.batch\\.worker\\.exports\\.config\\.ShedLockConfiguration"),
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
@MapperScan(basePackages = "com.example.batch.worker.exports.mapper.business", sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(E2eExportApplication.class, args);
    }
}
