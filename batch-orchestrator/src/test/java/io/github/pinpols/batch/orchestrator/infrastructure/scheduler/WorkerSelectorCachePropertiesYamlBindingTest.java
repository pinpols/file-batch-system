package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

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
 * 守护“yml 宇宙”：直接绑定真实 {@code application.yml}（不覆盖任何 env），断言
 * {@code batch.scheduler.worker-cache} 解析出的 enabled + ttl == feature-switches.md §1 文档口径。
 *
 * <p>类默认 {@code enabled=false}，但生产实际加载的是 yml 里 {@code ${...:true}} 的 fallback——只测类默认
 * 会漏掉 yml 与文档/实际行为相反的情况（同 RateLimitPropertiesYamlBindingTest 思路）。
 */
class WorkerSelectorCachePropertiesYamlBindingTest {

  private static WorkerSelectorCacheProperties bindFromApplicationYaml() throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> loaded =
        loader.load("application", new ClassPathResource("application.yml"));
    MutablePropertySources sources = new MutablePropertySources();
    loaded.forEach(sources::addLast);
    Binder binder = new Binder(
        ConfigurationPropertySources.from(sources),
        new PropertySourcesPlaceholdersResolver(sources));
    return binder
        .bind("batch.scheduler.worker-cache", WorkerSelectorCacheProperties.class)
        .get();
  }

  @Test
  @DisplayName("真 application.yml 无 env 覆盖时 worker-cache 默认开且 TTL=5s")
  void applicationYamlDefaultsMatchDocumentedContract() throws IOException {
    WorkerSelectorCacheProperties props = bindFromApplicationYaml();

    assertThat(props.isEnabled()).as("yml 默认应开启 worker 缓存").isTrue();
    assertThat(props.getTtlMillis()).as("TTL 默认 5s").isEqualTo(5_000L);
  }
}
