package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2-1(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md): 启动期 CORS allowlist 强校验单测——拒
 * {@code *} / {@code null} / 空白条目。
 */
class ConsoleSecurityPropertiesTest {

  @Test
  void corsAllowlist_empty_isAccepted() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of());
    p.validateCorsAllowedOrigins(); // 不抛
  }

  @Test
  void corsAllowlist_explicitOrigin_isAccepted() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://console.example.com", "https://admin.example.com"));
    p.validateCorsAllowedOrigins();
  }

  @Test
  void corsAllowlist_wildcardStar_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("*"));
    assertThatThrownBy(p::validateCorsAllowedOrigins)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("通配符 origin");
  }

  @Test
  void corsAllowlist_nullLiteral_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("null"));
    assertThatThrownBy(p::validateCorsAllowedOrigins).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void corsAllowlist_blankEntry_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://ok.example.com", "   "));
    assertThatThrownBy(p::validateCorsAllowedOrigins)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("空白条目");
  }

  @Test
  void corsAllowlist_wildcardMixedIn_isRejected() {
    ConsoleSecurityProperties p = new ConsoleSecurityProperties();
    p.setCorsAllowedOrigins(List.of("https://ok.example.com", "*"));
    assertThatThrownBy(p::validateCorsAllowedOrigins).isInstanceOf(IllegalStateException.class);
    assertThat(p.getCorsAllowedOrigins()).hasSize(2); // 不破坏数据
  }
}
