package io.github.pinpols.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** SPI 加固相关单测:DO 块分类 + dataSourceBean 白名单。无 docker / 无真实 BeanFactory。 */
class SqlTaskExecutorHardeningTest {

  private SqlExecutorProperties props;
  private SqlTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new SqlExecutorProperties();
    props.setForbidOsCapableRole(false);
    BeanFactory beanFactory = mock(BeanFactory.class);
    DataSource ds = mock(DataSource.class);
    executor = new SqlTaskExecutor(props, beanFactory, ds);
  }

  // ─── (a) DO block → DDL ──────────────────────────────────────────────────────

  @Test
  void doBlockIsClassifiedAsDdl() {
    assertThat(SqlTaskExecutor.detectStatementType("DO $$ BEGIN END $$")).isEqualTo("DDL");
  }

  @Test
  void doBlockLowercaseStillDdl() {
    assertThat(SqlTaskExecutor.detectStatementType("do $$ begin perform 1; end $$"))
        .isEqualTo("DDL");
  }

  // ─── (b) resolveDataSourceBeanName allowlist ─────────────────────────────────

  @Test
  void rejectsDataSourceBeanNotInAllowlist() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of("reportingDs"));

    assertThatThrownBy(
            () ->
                executor.resolveDataSourceBeanName(
                    Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "adminDs")))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("adminDs")
        .hasMessageContaining("allowedDataSourceBeans");
  }

  @Test
  void acceptsConfiguredDefaultBeanEvenWhenAllowlistEmpty() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of());

    String resolved =
        executor.resolveDataSourceBeanName(Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "primaryDs"));

    assertThat(resolved).isEqualTo("primaryDs");
  }

  @Test
  void acceptsBeanInAllowlist() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of("reportingDs"));

    assertThat(
            executor.resolveDataSourceBeanName(
                Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "reportingDs")))
        .isEqualTo("reportingDs");
  }

  @Test
  void fallsBackToConfiguredWhenParamMissing() {
    props.setDataSourceBeanName("primaryDs");

    assertThat(executor.resolveDataSourceBeanName(Map.of())).isEqualTo("primaryDs");
  }

  @Test
  void returnsNullWhenNoConfiguredAndNoParam() {
    props.setDataSourceBeanName(null);

    assertThat(executor.resolveDataSourceBeanName(Map.of())).isNull();
  }
}
