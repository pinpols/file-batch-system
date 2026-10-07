package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 守护"yml 宇宙":直接绑定真实 {@code application.yml}(不覆盖任何 env),断言 {@code batch.rate-limit} 解析出的 enabled +
 * 各阈值 == feature-switches.md §1 文档口径。
 *
 * <p>为何不只测 {@link RateLimitProperties} 类默认(见 {@code RateLimitPropertiesTest}):类默认对了、yml 里 {@code
 * ${ENV:default}} 的 fallback 写错(历史上 enabled fallback 是 false、阈值 fallback 是 0),生产实际加载的是 yml
 * 值而非类默认,会与文档/类默认相反且无测试发现。本类加载真 yml 并把 {@code ${...:default}} 解析成默认值(不设 env), 固化 yml 与文档一致。
 */
@DisplayName("限流参数与真实配置文件绑定,验证无环境变量覆盖时默认开启且各项阈值与文档口径一致")
class RateLimitPropertiesYamlBindingTest {

  private static RateLimitProperties bindFromApplicationYaml() throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> loaded =
        loader.load("application", new ClassPathResource("application.yml"));
    MutablePropertySources sources = new MutablePropertySources();
    loaded.forEach(sources::addLast);
    Binder binder = new Binder(
        ConfigurationPropertySources.from(sources),
        new PropertySourcesPlaceholdersResolver(sources));
    return binder.bind("batch.rate-limit", RateLimitProperties.class).get();
  }

  @Test
  @DisplayName("生产配置未设置环境变量时默认开启限流,各项阈值与文档口径一致")
  void shouldEnableRateLimitAndMatchDocumentedThresholds_whenNoEnvOverride() throws IOException {
    RateLimitProperties props = bindFromApplicationYaml();

    assertThat(props.isEnabled()).as("yml 默认应开启限流").isTrue();
    assertThat(props.getBucketConfigurationVersion()).as("令牌桶配置版本").isEqualTo(2L);
    assertThat(props.getMaxNewRequestsPerTenantPerMinute()).as("launch 阈值").isEqualTo(3000L);
    assertThat(props.getMaxReleaseRequestsPerTenantPerMinute()).as("release 阈值").isEqualTo(3000L);
    assertThat(props.getMaxRegisterRequestsPerTenantPerMinute())
        .as("register 阈值")
        .isEqualTo(300L);
    assertThat(props.getMaxClaimRequestsPerTenantPerMinute()).as("claim 阈值").isEqualTo(12000L);
    assertThat(props.getMaxReportRequestsPerTenantPerMinute()).as("report 阈值").isEqualTo(30000L);
  }
}
