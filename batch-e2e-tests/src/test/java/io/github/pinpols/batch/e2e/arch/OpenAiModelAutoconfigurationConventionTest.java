package io.github.pinpols.batch.e2e.arch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eDispatchApplication;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eProcessApplication;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiDecisionAutoConfiguration;
import org.springframework.ai.openai.OpenAiDecisionModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Spring AI 2.1 新增 {@link OpenAiDecisionAutoConfiguration}，且 {@code spring.ai.model.decision}
 * 缺省即视为 openai。E2E 应用不提供凭据，只排除 2.0 已有的 OpenAI 自动装配；漏掉 decision
 * 时客户端会在没有 apiKey 的情况下初始化并拒绝启动。
 */
@DisplayName("OpenAI 自动装配约定:端到端禁用决策模型,显式凭据仍可启用")
class OpenAiModelAutoconfigurationConventionTest {

    private static final List<Class<?>> E2E_APPLICATIONS = List.of(
            E2eAtomicApplication.class,
            E2eConsoleImportApplication.class,
            E2eDispatchApplication.class,
            E2eExportApplication.class,
            E2eImportApplication.class,
            E2eOrchestratorApplication.class,
            E2eProcessApplication.class,
            E2eReportRecoveryOrchestratorApplication.class,
            E2eTriggerApplication.class);

    private final ApplicationContextRunner decisionRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAiDecisionAutoConfiguration.class));

    @Test
    @DisplayName("端到端应用排除聊天模型时同时排除决策模型,因此不会在无凭据时装配决策模型")
    void shouldExcludeDecisionModel_whenE2eApplicationDisablesOpenAiChat() {
        for (Class<?> application : E2E_APPLICATIONS) {
            Set<Class<?>> excluded = excludedAutoConfigurations(application);
            assertThat(excluded)
                    .as("%s 已排除 OpenAI 聊天自动装配", application.getSimpleName())
                    .contains(OpenAiChatAutoConfiguration.class);
            assertThat(excluded)
                    .as("%s 必须同时排除默认启用的 OpenAI 决策自动装配", application.getSimpleName())
                    .contains(OpenAiDecisionAutoConfiguration.class);
        }
    }

    @Test
    @DisplayName("真实 Console 应用入口排除未使用的 OpenAI 决策模型")
    void shouldExcludeDecisionModel_inConsoleApplication() {
        assertThat(excludedAutoConfigurations(BatchConsoleApiApplication.class))
                .contains(OpenAiDecisionAutoConfiguration.class);
    }

    @Test
    @DisplayName("未配置任何凭据时,默认决策模型自动装配拒绝启动")
    void shouldFailContext_whenDecisionModelHasNoCredential() {
        decisionRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("At least one credential source must be specified");
        });
    }

    @Test
    @DisplayName("显式提供 API key 并选择 openai 决策模型时,仍然创建决策模型")
    void shouldCreateDecisionModel_whenCredentialAndOpenAiDecisionAreConfigured() {
        decisionRunner
                .withPropertyValues("spring.ai.model.decision=openai", "spring.ai.openai.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenAiDecisionModel.class);
                });
    }

    @Test
    @DisplayName("API key 为空字符串时按无认证模式启动,不要求真实凭据")
    void shouldStartWithoutCredential_whenApiKeyIsExplicitlyEmpty() {
        decisionRunner.withPropertyValues("spring.ai.openai.api-key=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenAiDecisionModel.class);
        });
    }

    private static Set<Class<?>> excludedAutoConfigurations(Class<?> application) {
        EnableAutoConfiguration annotation =
                AnnotatedElementUtils.findMergedAnnotation(application, EnableAutoConfiguration.class);
        assertThat(annotation).as(application.getSimpleName()).isNotNull();
        return Arrays.stream(annotation.exclude()).collect(Collectors.toSet());
    }
}
