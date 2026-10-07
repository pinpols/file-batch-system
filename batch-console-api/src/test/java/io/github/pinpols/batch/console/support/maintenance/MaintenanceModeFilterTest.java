package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("维护模式过滤器: 全屏拦截、只读放行与管理员旁路")
class MaintenanceModeFilterTest {

  private ConsoleMaintenanceProperties properties;
  private MaintenanceStateMapper mapper;
  private MaintenanceStateHolder stateHolder;
  private MaintenanceStateMetrics metrics;
  private MaintenanceModeFilter filter;
  private MockHttpServletResponse response;
  private AtomicBoolean chainInvoked;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    properties = new ConsoleMaintenanceProperties();
    mapper = mock(MaintenanceStateMapper.class);
    when(mapper.selectSingleton()).thenAnswer(invocation -> entityFromProperties());
    when(mapper.updateIfVersion(
            any(boolean.class), any(boolean.class), any(), any(), any(), any(), anyLong()))
        .thenReturn(1);
    stateHolder = new MaintenanceStateHolder(properties, mapper, new ObjectMapper());
    stateHolder.initFromProperties();
    metrics = mock(MaintenanceStateMetrics.class);
    filter = new MaintenanceModeFilter(stateHolder, metrics, new ObjectMapper());
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
  @DisplayName("维护开关关闭时请求直接放行且响应保持 200")
  void shouldPassThrough_whenMaintenanceDisabled() throws Exception {
    properties.setEnabled(false);
    filter.doFilterInternal(get("/api/console/jobs"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("维护开启时普通路径被拦截, 返回 503 与维护标记并带出自定义提示")
  void shouldBlockWith503_whenMaintenanceEnabledForRegularPath() throws Exception {
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
  @DisplayName("维护期白名单路径被放行, 过滤链继续执行且响应为 200")
  void shouldAllowWhitelistedPath_whenMaintenanceEnabled() throws Exception {
    properties.setEnabled(true);
    refresh();
    filter.doFilterInternal(get("/api/console/system/maintenance"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("维护期健康检查通配路径被放行, 过滤链继续执行")
  void shouldAllowActuatorWildcard_whenMaintenanceEnabled() throws Exception {
    properties.setEnabled(true);
    refresh();
    filter.doFilterInternal(get("/actuator/health"), response, chain);
    assertThat(chainInvoked).isTrue();
  }

  @Test
  @DisplayName("维护全屏拦截时管理员写请求仍被放行, 响应头标记管理员旁路")
  void shouldAllowAdminWrite_whenMaintenanceFullBlock() throws Exception {
    // 维护期全屏 503,但 ROLE_ADMIN 可旁路继续操作(运维场景);响应头标 admin-bypass,
    // 前端 banner 据此显示"当前为维护期 admin 旁路"提示。
    properties.setEnabled(true);
    properties.setReadOnly(false);
    refresh();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            "ops-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/console/jobs");
    filter.doFilterInternal(post, response, chain);

    assertThat(chainInvoked).as("admin POST 应被放行").isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("admin-bypass");
  }

  @Test
  @DisplayName("维护期已认证的普通用户仍被拦截, 返回 503 与维护标记")
  void shouldBlockNonAdmin_whenMaintenanceEnabledEvenIfAuthenticated() throws Exception {
    // 普通租户用户(非 ADMIN)维护期仍被 503;防止 admin 判定逻辑放宽到任何 authority。
    properties.setEnabled(true);
    refresh();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            "viewer", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_USER"))));

    filter.doFilterInternal(get("/api/console/jobs"), response, chain);

    assertThat(chainInvoked).as("非 admin 应被 503").isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Maintenance")).isEqualTo("blocked");
  }

  @Test
  @DisplayName("维护只读模式下查询放行并标记只读, 写请求被拦截返回 503")
  void shouldAllowGetAndBlockWrite_whenMaintenanceReadOnly() throws Exception {
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

  private MaintenanceStateEntity entityFromProperties() {
    MaintenanceStateEntity entity = new MaintenanceStateEntity();
    entity.setEnabled(properties.isEnabled());
    entity.setReadOnly(properties.isReadOnly());
    entity.setMessage(properties.getMessage());
    entity.setEtaAt(properties.getEtaAt());
    entity.setAffectedServicesJson(new ObjectMapper()
        .valueToTree(
            properties.getAffectedServices() == null ? List.of() : properties.getAffectedServices())
        .toString());
    entity.setVersion(0L);
    return entity;
  }
}
