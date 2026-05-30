package com.example.batch.console.domain.audit.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleAiProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 守护 AI 助手鉴权 4 条决策路径:
 *
 * <ol>
 *   <li>未登录 / Anonymous → FORBIDDEN
 *   <li>用户名命中 allowedUsers → 放行
 *   <li>authority 命中 allowedAuthorities → 放行
 *   <li>两个白名单都不命中 → FORBIDDEN
 * </ol>
 */
class ConsoleAiAuthorizationServiceTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private ConsoleAiAuthorizationService newService(
      List<String> allowedUsers, List<String> allowedAuthorities) {
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.setAllowedUsers(allowedUsers);
    properties.setAllowedAuthorities(allowedAuthorities);
    return new ConsoleAiAuthorizationService(properties);
  }

  @Test
  @DisplayName("未登录 (SecurityContext empty) → FORBIDDEN")
  void assertAllowed_noAuthentication_throwsForbidden() {
    ConsoleAiAuthorizationService service = newService(List.of("admin"), List.of("ROLE_ADMIN"));
    assertThatThrownBy(service::assertAllowed)
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                org.assertj.core.api.Assertions.assertThat(((BizException) ex).getCode())
                    .isEqualTo(ResultCode.FORBIDDEN));
  }

  @Test
  @DisplayName("AnonymousAuthenticationToken → FORBIDDEN")
  void assertAllowed_anonymousToken_throwsForbidden() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    ConsoleAiAuthorizationService service = newService(List.of("admin"), List.of("ROLE_ADMIN"));
    assertThatThrownBy(service::assertAllowed).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("用户名命中 allowedUsers → 放行(即使 authority 不命中)")
  void assertAllowed_userMatched_passes() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    ConsoleAiAuthorizationService service =
        newService(List.of("alice"), List.of("ROLE_ADMIN")); // authority 不在白名单
    assertThatCode(service::assertAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("authority 命中 allowedAuthorities → 放行(即使 username 不命中)")
  void assertAllowed_authorityMatched_passes() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "bob", null, List.of(new SimpleGrantedAuthority("ROLE_AUDITOR"))));
    ConsoleAiAuthorizationService service =
        newService(List.of("alice"), List.of("ROLE_AUDITOR")); // user 不在白名单
    assertThatCode(service::assertAllowed).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("两个白名单都不命中 → FORBIDDEN")
  void assertAllowed_neitherMatched_throwsForbidden() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "charlie", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    ConsoleAiAuthorizationService service =
        newService(List.of("alice", "bob"), List.of("ROLE_ADMIN", "ROLE_AUDITOR"));
    assertThatThrownBy(service::assertAllowed).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("空白名单 + 任何用户都登录态 → 仍 FORBIDDEN(默认拒绝,不是'空 = 允许全部')")
  void assertAllowed_emptyWhitelists_throwsForbidden() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "anyone", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    ConsoleAiAuthorizationService service = newService(List.of(), List.of());
    assertThatThrownBy(service::assertAllowed).isInstanceOf(BizException.class);
  }
}
