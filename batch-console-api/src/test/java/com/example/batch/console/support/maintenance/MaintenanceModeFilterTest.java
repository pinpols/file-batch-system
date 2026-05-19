package com.example.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.config.ConsoleMaintenanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MaintenanceModeFilterTest {

  private ConsoleMaintenanceProperties properties;
  private MaintenanceModeFilter filter;
  private MockHttpServletResponse response;
  private AtomicBoolean chainInvoked;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    properties = new ConsoleMaintenanceProperties();
    filter = new MaintenanceModeFilter(properties, new ObjectMapper());
    response = new MockHttpServletResponse();
    chainInvoked = new AtomicBoolean(false);
    chain = (req, resp) -> chainInvoked.set(true);
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
    filter.doFilterInternal(get("/api/console/system/maintenance"), response, chain);
    assertThat(chainInvoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void allowActuatorWildcardDuringMaintenance() throws Exception {
    properties.setEnabled(true);
    filter.doFilterInternal(get("/actuator/health"), response, chain);
    assertThat(chainInvoked).isTrue();
  }

  @Test
  void readOnlyAllowsGetButBlocksWrite() throws Exception {
    properties.setEnabled(true);
    properties.setReadOnly(true);

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
