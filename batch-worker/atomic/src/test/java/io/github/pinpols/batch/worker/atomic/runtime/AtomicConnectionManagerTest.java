package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AtomicConnectionManager} 纯逻辑单测 — DataSource 解析 + 白名单守门。
 *
 * <p>requireNonOsCapableRole / withConnection 走真 JDBC,留给集成测覆盖(testcontainers PG)。
 */
@DisplayName("原子连接管理器: 数据源解析与白名单守门")
class AtomicConnectionManagerTest {

  @Test
  @DisplayName("参数为空或纯空白时应回退到配置的数据源, 保证默认路径可用")
  void shouldFallBackToConfigured_whenParamBlank() {
    assertThat(AtomicConnectionManager.resolveDataSourceBeanName("ds1", null, Set.of()))
        .isEqualTo("ds1");
    assertThat(AtomicConnectionManager.resolveDataSourceBeanName("ds1", "", Set.of()))
        .isEqualTo("ds1");
    assertThat(AtomicConnectionManager.resolveDataSourceBeanName("ds1", "  ", Set.of()))
        .isEqualTo("ds1");
  }

  @Test
  @DisplayName("参数与配置数据源一致时应正常返回, 不触发白名单校验失败")
  void shouldKeepParam_whenSameAsConfigured() {
    assertThat(AtomicConnectionManager.resolveDataSourceBeanName("ds1", "ds1", Set.of()))
        .isEqualTo("ds1");
  }

  @Test
  @DisplayName("参数数据源在白名单内时应允许切换")
  void shouldAcceptParam_whenOnAllowList() {
    assertThat(
            AtomicConnectionManager.resolveDataSourceBeanName("ds1", "ds2", Set.of("ds2", "ds3")))
        .isEqualTo("ds2");
  }

  @Test
  @DisplayName("参数数据源不在白名单内时应拒绝, 并提示不在允许清单")
  void shouldRejectParam_whenNotOnAllowList() {
    assertThatThrownBy(
            () -> AtomicConnectionManager.resolveDataSourceBeanName("ds1", "evil", Set.of("ds2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in allowedDataSourceBeans");
  }

  @Test
  @DisplayName("未配置白名单时切换数据源应被拒绝, 默认关闭切换能力")
  void shouldRejectParam_whenAllowListMissing() {
    assertThatThrownBy(() -> AtomicConnectionManager.resolveDataSourceBeanName("ds1", "ds2", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("未配置默认数据源时, 白名单内的参数数据源仍应可用")
  void shouldAcceptParam_whenConfiguredMissingAndParamAllowed() {
    // null configured = default dataSource;param 可以切换到白名单内 bean
    assertThat(AtomicConnectionManager.resolveDataSourceBeanName(null, "ds2", Set.of("ds2")))
        .isEqualTo("ds2");
  }

  @Test
  @DisplayName("连接选项默认不自动提交, 不只读且禁止高权限角色, 只读与链式覆盖按预期生效")
  void shouldExposeSafeDefaults_whenOptionsBuilt() {
    AtomicConnectionManager.Options d = AtomicConnectionManager.Options.defaults();
    assertThat(d.autoCommit).isFalse();
    assertThat(d.readOnly).isFalse();
    assertThat(d.forbidOsCapableRole).isTrue();

    AtomicConnectionManager.Options ro = AtomicConnectionManager.Options.forReadOnlyTransaction();
    assertThat(ro.readOnly).isTrue();

    AtomicConnectionManager.Options modified = AtomicConnectionManager.Options.defaults()
        .withAutoCommit(true)
        .withForbidOsCapableRole(false);
    assertThat(modified.autoCommit).isTrue();
    assertThat(modified.forbidOsCapableRole).isFalse();
  }
}
