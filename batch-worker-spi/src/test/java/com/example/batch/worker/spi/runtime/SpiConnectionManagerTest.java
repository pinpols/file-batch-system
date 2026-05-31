package com.example.batch.worker.spi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SpiConnectionManager} 纯逻辑单测 — DataSource 解析 + 白名单守门。
 *
 * <p>requireNonOsCapableRole / withConnection 走真 JDBC,留给集成测覆盖(testcontainers PG)。
 */
class SpiConnectionManagerTest {

  @Test
  void resolveBeanNameFallsBackToConfiguredWhenParamNull() {
    assertThat(SpiConnectionManager.resolveDataSourceBeanName("ds1", null, Set.of()))
        .isEqualTo("ds1");
    assertThat(SpiConnectionManager.resolveDataSourceBeanName("ds1", "", Set.of()))
        .isEqualTo("ds1");
    assertThat(SpiConnectionManager.resolveDataSourceBeanName("ds1", "  ", Set.of()))
        .isEqualTo("ds1");
  }

  @Test
  void resolveBeanNameKeepsParamWhenSameAsConfigured() {
    assertThat(SpiConnectionManager.resolveDataSourceBeanName("ds1", "ds1", Set.of()))
        .isEqualTo("ds1");
  }

  @Test
  void resolveBeanNameAcceptsParamOnAllowList() {
    assertThat(SpiConnectionManager.resolveDataSourceBeanName("ds1", "ds2", Set.of("ds2", "ds3")))
        .isEqualTo("ds2");
  }

  @Test
  void resolveBeanNameRejectsParamNotOnAllowList() {
    assertThatThrownBy(
            () -> SpiConnectionManager.resolveDataSourceBeanName("ds1", "evil", Set.of("ds2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in allowedDataSourceBeans");
  }

  @Test
  void resolveBeanNameRejectsParamWhenAllowListNull() {
    assertThatThrownBy(() -> SpiConnectionManager.resolveDataSourceBeanName("ds1", "ds2", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveBeanNameAllowsNullConfiguredWithParamOnList() {
    // null configured = default dataSource;param 可以切换到白名单内 bean
    assertThat(SpiConnectionManager.resolveDataSourceBeanName(null, "ds2", Set.of("ds2")))
        .isEqualTo("ds2");
  }

  @Test
  void optionsDefaultsAndBuilders() {
    SpiConnectionManager.Options d = SpiConnectionManager.Options.defaults();
    assertThat(d.autoCommit).isFalse();
    assertThat(d.readOnly).isFalse();
    assertThat(d.forbidOsCapableRole).isTrue();

    SpiConnectionManager.Options ro = SpiConnectionManager.Options.readOnly();
    assertThat(ro.readOnly).isTrue();

    SpiConnectionManager.Options modified =
        SpiConnectionManager.Options.defaults().withAutoCommit(true).withForbidOsCapableRole(false);
    assertThat(modified.autoCommit).isTrue();
    assertThat(modified.forbidOsCapableRole).isFalse();
  }
}
