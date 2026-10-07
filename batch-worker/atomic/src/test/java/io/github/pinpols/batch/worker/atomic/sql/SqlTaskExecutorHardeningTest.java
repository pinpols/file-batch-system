package io.github.pinpols.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** SPI 加固相关单测:DO 块分类 + dataSourceBean 白名单。无 docker / 无真实 BeanFactory。 */
@DisplayName("数据库执行器加固: 语句类型识别与数据源白名单")
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
  @DisplayName("匿名代码块语句应被识别为结构变更类型")
  void shouldClassifyAsDdl_whenStatementIsDoBlock() {
    assertThat(SqlTaskExecutor.detectStatementType("DO $$ BEGIN END $$")).isEqualTo("DDL");
  }

  @Test
  @DisplayName("匿名代码块使用小写关键字时仍应识别为结构变更类型")
  void shouldClassifyAsDdl_whenDoBlockLowercase() {
    assertThat(SqlTaskExecutor.detectStatementType("do $$ begin perform 1; end $$"))
        .isEqualTo("DDL");
  }

  // ─── (b) resolveDataSourceBeanName allowlist ─────────────────────────────────

  @Test
  @DisplayName("指定的数据源不在白名单内时应拒绝执行并给出提示")
  void shouldReject_whenDataSourceBeanNotAllowed() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of("reportingDs"));

    assertThatThrownBy(() ->
            executor.resolveDataSourceBeanName(Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "adminDs")))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("adminDs")
        .hasMessageContaining("allowedDataSourceBeans");
  }

  @Test
  @DisplayName("白名单为空时配置的默认数据源仍应可用, 保证默认路径不被误伤")
  void shouldAcceptConfiguredDefault_whenAllowlistEmpty() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of());

    String resolved =
        executor.resolveDataSourceBeanName(Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "primaryDs"));

    assertThat(resolved).isEqualTo("primaryDs");
  }

  @Test
  @DisplayName("数据源在白名单内时应允许使用")
  void shouldAccept_whenDataSourceBeanInAllowlist() {
    props.setDataSourceBeanName("primaryDs");
    props.setAllowedDataSourceBeans(Set.of("reportingDs"));

    assertThat(executor.resolveDataSourceBeanName(
            Map.of(SqlTaskExecutor.PARAM_DS_BEAN, "reportingDs")))
        .isEqualTo("reportingDs");
  }

  @Test
  @DisplayName("未指定数据源参数时应回退到配置的默认数据源")
  void shouldFallBackToConfigured_whenParamMissing() {
    props.setDataSourceBeanName("primaryDs");

    assertThat(executor.resolveDataSourceBeanName(Map.of())).isEqualTo("primaryDs");
  }

  @Test
  @DisplayName("既无配置也未传参数时应返回空, 由上层决定默认数据源")
  void shouldReturnNull_whenNeitherConfiguredNorParam() {
    props.setDataSourceBeanName(null);

    assertThat(executor.resolveDataSourceBeanName(Map.of())).isNull();
  }
}
