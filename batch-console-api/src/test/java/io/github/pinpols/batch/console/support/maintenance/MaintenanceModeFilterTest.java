package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaintenanceModeFilterTest {

  private ConsoleMaintenanceProperties properties;
  private MaintenanceStateHolder stateHolder;
  private MaintenanceModeFilter filter;
  private MockHttpServletResponse response;
  private AtomicBoolean chainInvoked;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    properties = new ConsoleMaintenanceProperties();
    stateHolder = new MaintenanceStateHolder(properties);
    stateHolder.initFromProperties();
    filter = new MaintenanceModeFilter(stateHolder, new ObjectMapper());
    response = new MockHttpServletResponse();
    chainInvoked = new AtomicBoolean(false);
    chain = (req, resp) -> chainInvoked.set(true);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void refresh() {
    stateHolder.initFromProperties();
  }

  @Test
  void passThroughWhenMaintenanceDisabled() throws Exception {
    properties.setEnabled(false);
    filter.doFilterInternal(get("/api/console/jobs"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void block503WhenEnabledForRegularPath() throws Exception {
    properties.setEnabled(true);
    properties.setMessage("DB switch in progress");
    refresh();
    filter.doFilterInternal(get("/api/console/jobs"), response, chain);
    assertThat(chainInvoked).isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("blocked");
    assertThat(response.getContentAsString())
        .contains("\"maintenance\":true", "DB switch in progress");
  }

  @Test
  void allowWhitelistedPathsDuringMaintenance() throws Exception {
    properties.setEnabled(true);
    refresh();
    filter.doFilterInternal(get("/api/console/system/maintenance"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void allowActuatorWildcardDuringMaintenance() throws Exception {
    properties.setEnabled(true);
    refresh();
    filter.doFilterInternal(get("/actuator/health"), response, chain);
    assertThat(chainInvoked).isTrue();
  }

  @Test
  void adminBypassAllowsWriteEvenInFullBlockMode() throws Exception {
    // 维护期全屏 503,但 ROLE_ADMIN 可旁路继续操作(运维场景);响应头标 admin-bypass,
    // 前端 banner 据此显示"当前为维护期 admin 旁路"提示。
    properties.setEnabled(true);
    properties.setReadOnly(false);
    refresh();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "ops-admin", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/console/jobs");
    filter.doFilterInternal(post, response, chain);

    assertThat(chainInvoked).as("admin POST 应被放行").isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("admin-bypass");
  }

  @Test
  void nonAdminBlockedEvenWithAuthenticationDuringMaintenance() throws Exception {
    // 普通登录用户(ROLE_VIEWER 等非 ADMIN)维护期仍被 503;防止 admin 判定逻辑放宽到任何 authority。
    properties.setEnabled(true);
    refresh();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "viewer", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));

    filter.doFilterInternal(get("/api/console/jobs"), response, chain);

    assertThat(chainInvoked).as("非 admin 应被 503").isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("blocked");
  }

  @Test
  void readOnlyAllowsGetButBlocksWrite() throws Exception {
    properties.setEnabled(true);
    properties.setReadOnly(true);
    refresh();

    // GET: passes through with X-Maintenance: read-only
    filter.doFilterInternal(get("/api/console/jobs"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("read-only");

    // POST: blocked
    MockHttpServletResponse writeResp = new MockHttpServletResponse();
    AtomicBoolean writeChainInvoked = new AtomicBoolean(false);
    FilterChain writeChain = (req, resp) -> writeChainInvoked.set(true);
    MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/console/jobs");
    filter.doFilterInternal(post, writeResp, writeChain);
    assertThat(writeChainInvoked).isFalse();
    assertThat(writeResp.getStatus()).isEqualTo(503);
    assertThat(writeResp.getHeader("X-Maintenance")).isEqualTo("read-only");
  }

  private MockHttpServletRequest get(String path) {
    return new MockHttpServletRequest("GET", path);
  }
}
