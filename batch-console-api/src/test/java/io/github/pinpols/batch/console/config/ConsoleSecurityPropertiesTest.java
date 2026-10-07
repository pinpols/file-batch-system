package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-1(2026-06-03,docs/archive/analysis/2026-06-03-deep-scan-be-security.md): 启动期 CORS allowlist 强校验单测——拒
 * {@code *} / {@code null} / 空白条目。
 */
@DisplayName("控制台安全属性校验: 默认角色白名单与跨域来源列表的启动期强校验")
class ConsoleSecurityPropertiesTest {

  @Test
  @DisplayName("默认角色全部为正式角色时,校验通过且不抛异常")
  void defaultAuthorities_fourFormalRolesAreAccepted() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setDefaultAuthorities(List.of("ROLE_ADMIN", "ROLE_AUDITOR"));

    p.validateDefaultAuthorities();
  }

  @Test
  @DisplayName("默认角色含历史遗留角色时,校验失败并提示只接受四个正式角色")
  void defaultAuthorities_legacyRoleIsRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setDefaultAuthorities(List.of("ROLE_USER"));

    assertThatThrownBy(p::validateDefaultAuthorities)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("four formal console roles");
  }

  @Test
  @DisplayName("跨域来源列表为空时,校验通过且不抛异常")
  void corsAllowlist_empty_isAccepted() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of());
    p.validateCorsAllowedOrigins(); // 不抛
  }

  @Test
  @DisplayName("显式列出的具体来源,校验通过")
  void corsAllowlist_explicitOrigin_isAccepted() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://console.example.com", "https://admin.example.com"));
    p.validateCorsAllowedOrigins();
  }

  @Test
  @DisplayName("来源列表含通配符星号时,校验失败并提示禁止通配来源")
  void corsAllowlist_wildcardStar_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("*"));
    assertThatThrownBy(p::validateCorsAllowedOrigins)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wildcard origins");
  }

  @Test
  @DisplayName("来源列表含字面量空值字符串时,校验失败")
  void corsAllowlist_nullLiteral_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("null"));
    assertThatThrownBy(p::validateCorsAllowedOrigins).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("来源列表含纯空白条目时,校验失败并提示存在空白条目")
  void corsAllowlist_blankEntry_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://ok.example.com", "   "));
    assertThatThrownBy(p::validateCorsAllowedOrigins)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blank entry");
  }

  @Test
  @DisplayName("通配符混在具体来源中时,校验失败且不改动原始列表内容")
  void corsAllowlist_wildcardMixedIn_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://ok.example.com", "*"));
    assertThatThrownBy(p::validateCorsAllowedOrigins).isInstanceOf(IllegalStateException.class);
    assertThat(p.getCorsAllowedOrigins()).hasSize(2); // 不破坏数据
  }
}
