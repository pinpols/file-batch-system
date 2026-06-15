package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R3-P1-13 补：覆盖 {@link BatchSecurityProperties#validateSecuritySettings()} 的所有分支。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>prod / production / staging / uat / preprod 等 PROD_LIKE profile 下 {@code bypassMode=true} →
 *       抛 FATAL
 *   <li>占位符密钥（CHANGE_ME / change-me / changeme / placeholder / secret 等大小写下划线连字符变体）→ 抛 FATAL
 *   <li>密钥长度 < 16 → 抛 FATAL（弱密钥强度兜底）
 *   <li>非 prod profile 下完全宽松（local / dev / test）
 *   <li>无 Environment（纯单元测试场景）安全早返回
 * </ul>
 */
class BatchSecurityPropertiesTest {

  private static BatchSecurityProperties newProps(String activeProfile, boolean bypassMode) {
    BatchSecurityProperties props = new BatchSecurityProperties();
    props.setBypassMode(bypassMode);
    MockEnvironment env = new MockEnvironment();
    if (activeProfile != null) {
      env.setActiveProfiles(activeProfile);
    }
    ReflectionTestUtils.setField(props, "environment", env);
    return props;
  }

  // ─── bypass-mode 守护 ─────────────────────────────────────────────────

  @Test
  void prodProfile_bypassModeTrue_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", true);
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bypass-mode=true 在生产 profile 下被禁止");
  }

  @Test
  void stagingProfile_bypassModeTrue_throwsFatal() {
    BatchSecurityProperties props = newProps("staging", true);
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bypass-mode=true 在生产 profile 下被禁止");
  }

  @Test
  void uatProfile_bypassModeTrue_throwsFatal() {
    BatchSecurityProperties props = newProps("uat", true);
    assertThatThrownBy(props::validateSecuritySettings).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void preprodProfile_bypassModeTrue_throwsFatal() {
    BatchSecurityProperties props = newProps("preprod", true);
    assertThatThrownBy(props::validateSecuritySettings).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void localProfile_bypassModeTrue_allowed() {
    BatchSecurityProperties props = newProps("local", true);
    props.setInternalSecret("strong-secret-at-least-16-chars-long");
    // 不在 prod-like 名单里，bypass=true 应该被允许
    props.validateSecuritySettings();
  }

  // ─── 占位符密钥校验 ──────────────────────────────────────────────────

  // ─── 非 prod:默认/弱凭据只 WARN 不阻断(审计 #4 第二层守护)──────────────

  @Test
  void localProfile_weakReplicaDbPassword_warnsNotThrows() {
    // 非 prod + 默认弱口令 batch_pass_123 → 只 WARN(兜 prod fail-fast),启动不被阻断
    BatchSecurityProperties props = new BatchSecurityProperties();
    props.setInternalSecret("strong-secret-at-least-16-chars-long");
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");
    env.setProperty("batch.console.read-replica.primary.password", "batch_pass_123");
    ReflectionTestUtils.setField(props, "environment", env);
    assertThatCode(props::validateSecuritySettings).doesNotThrowAnyException();
  }

  @Test
  void prodProfile_weakReplicaDbPassword_throwsFatal() {
    // 对照:prod 下同样的弱口令必须 fail-fast 拒绝启动
    BatchSecurityProperties props = new BatchSecurityProperties();
    props.setInternalSecret("strong-secret-at-least-16-chars-long");
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.password", "strong-real-password");
    env.setProperty("batch.console.read-replica.primary.password", "batch_pass_123");
    ReflectionTestUtils.setField(props, "environment", env);
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("默认弱口令");
  }

  @Test
  void prodProfile_defaultInternalSecret_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    // internalSecret 默认值 "internal-secret"（15 char）会先在长度校验处被拒
    // （validateNotPlaceholder 内 MIN_SECRET_LENGTH=16 检查在 "仍为默认值" 等值比较之前）
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("密钥强度不足");
  }

  @Test
  void prodProfile_placeholderCHANGE_ME_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("CHANGE_ME_STRONG_INTERNAL_SECRET");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("仍为占位符");
  }

  @Test
  void prodProfile_placeholderChangeMeLowercase_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("change-me-something");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("占位符");
  }

  @Test
  void prodProfile_placeholderChangeme_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("changeme123456789");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("占位符");
  }

  @Test
  void prodProfile_placeholderTODO_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("todo-replace-with-real");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("占位符");
  }

  // ─── 长度强度校验 ────────────────────────────────────────────────────

  @Test
  void prodProfile_secretTooShort_throwsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("short15-chars-x"); // 15 chars < MIN_SECRET_LENGTH=16
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("密钥强度不足");
  }

  @Test
  void prodProfile_secretBlankThrowsFatal() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret(""); // blank
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("为空");
  }

  // ─── 通过场景 ────────────────────────────────────────────────────────

  @Test
  void prodProfile_strongSecret_passes() {
    BatchSecurityProperties props = newProps("prod", false);
    props.setInternalSecret("a-strong-random-secret-from-vault-32-bytes");
    // postgres password 在 environment 里未设置 → "" → 抛"为空"，所以单独验证 internalSecret 这条路径
    // 设置 spring.datasource.password 避免下游校验报错
    MockEnvironment env = (MockEnvironment) ReflectionTestUtils.getField(props, "environment");
    env.setProperty("spring.datasource.password", "real-strong-pg-password-2026");
    props.validateSecuritySettings(); // no throw
  }

  @Test
  void noEnvironment_safeEarlyReturn() {
    BatchSecurityProperties props = new BatchSecurityProperties();
    props.setBypassMode(true);
    // environment=null → 跳过所有校验
    props.validateSecuritySettings();
  }

  @Test
  void testProfile_completelyRelaxed() {
    BatchSecurityProperties props = newProps("test", true);
    // test profile 不在 prod-like 名单，bypass + 默认密钥都不报错
    props.validateSecuritySettings();
  }

  // ─── isProductionProfile 大小写归一化 ────────────────────────────────

  @Test
  void prodProfile_caseInsensitive_PROD() {
    BatchSecurityProperties props = newProps("PROD", true);
    assertThatThrownBy(props::validateSecuritySettings).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void prodProfile_caseInsensitive_Production() {
    BatchSecurityProperties props = newProps("Production", true);
    assertThatThrownBy(props::validateSecuritySettings).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void prodLike_preDashProd_isProductionProfile() {
    BatchSecurityProperties props = newProps("pre-prod", true);
    assertThatThrownBy(props::validateSecuritySettings).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unknownProfile_failSecure_treatedAsProduction() {
    // audit fix(fail-open → fail-secure):未知 / 未登记的 profile 不再被当作非生产放行。
    // 既无 prod-like 也无已识别的非生产 profile → 按生产对待 → bypass=true 必须被拒。
    BatchSecurityProperties props = newProps("custom-env", true);
    props.setInternalSecret("strong-non-default-secret-2026");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bypass-mode=true 在生产 profile 下被禁止");
  }

  @Test
  void emptyProfile_failSecure_treatedAsProduction() {
    // 空激活集(部署忘配 SPRING_PROFILES_ACTIVE)同样 fail-secure 当生产。
    BatchSecurityProperties props = newProps(null, true);
    props.setInternalSecret("strong-non-default-secret-2026");
    assertThatThrownBy(props::validateSecuritySettings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bypass-mode=true 在生产 profile 下被禁止");
  }

  @Test
  void e2eProfile_recognizedNonProd_allowsBypass() {
    // 集成测常用 {"test","e2e"};e2e 已登记为非生产,bypass=true 应放行。
    BatchSecurityProperties props = newProps("e2e", true);
    props.setInternalSecret("strong-secret-at-least-16-chars-long");
    props.validateSecuritySettings();
    assertThat(props.isBypassMode()).isTrue();
  }
}
