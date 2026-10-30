package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.StatusCountView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ClusterStatusCountView;
import java.io.IOException;
import java.util.List;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 对真实local配置启用的别名包执行注册，避免测试配置遗漏启动期名称冲突。 */
@DisplayName("MyBatis本地别名：实际local配置中的全部领域类可同时注册")
class MyBatisLocalAliasesTest {

  @Test
  @DisplayName("local领域包的统计视图别名不冲突，并分别指向集群和仪表盘投影")
  void shouldRegisterLocalAliasesForClusterAndDashboardViews() throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    ClassPathResource localConfiguration = new ClassPathResource("application-local.yml");
    List<PropertySource<?>> propertySources = loader.load("local", localConfiguration);
    String aliasesPackage = null;
    for (PropertySource<?> source : propertySources) {
      Object configuredPackage = source.getProperty("mybatis.type-aliases-package");
      if (configuredPackage instanceof String packageName) {
        aliasesPackage = packageName;
      }
    }
    assertThat(aliasesPackage).isNotBlank();

    TypeAliasRegistry aliases = new TypeAliasRegistry();
    aliases.registerAliases(aliasesPackage);
    Class<?> clusterAlias = aliases.resolveAlias("ClusterStatusCountView");
    Class<?> dashboardAlias = aliases.resolveAlias("StatusCountView");

    assertThat(clusterAlias).isEqualTo(ClusterStatusCountView.class);
    assertThat(dashboardAlias).isEqualTo(StatusCountView.class);
  }
}
