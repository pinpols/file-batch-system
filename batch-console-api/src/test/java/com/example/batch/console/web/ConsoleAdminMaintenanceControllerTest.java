package com.example.batch.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.config.ConsoleMaintenanceProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P0: ConsoleAdminMaintenanceController 热更新 + 状态序列化。
 *
 * <p>覆盖:GET 当前状态、PUT 部分字段更新、affectedServices 透传、AtomicReference 不可变替换、@AuditAction
 * 注解仍写审计(本测试不验证审计落库, 由 AuditAspect 单测覆盖)。
 */
class ConsoleAdminMaintenanceControllerTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MaintenanceStateHolder stateHolder;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    ConsoleMaintenanceProperties props = new ConsoleMaintenanceProperties();
    stateHolder = new MaintenanceStateHolder(props);
    // PostConstruct 在 standalone setup 下需手动调
    ReflectionTestUtils.invokeMethod(stateHolder, "initFromProperties");

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleAdminMaintenanceController(stateHolder, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void getShouldReturnInitialDisabledState() throws Exception {
    mockMvc
        .perform(get("/api/console/admin/system/maintenance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.readOnly").value(false));
  }

  @Test
  void putShouldHotUpdateStateInPlace() throws Exception {
    String body =
        """
        {"enabled":true,"readOnly":true,"message":"DB 灰度中","etaAt":"2026-05-20T15:00:00Z",
         "affectedServices":["job-schedule","file-download"]}
        """;
    mockMvc
        .perform(
            put("/api/console/admin/system/maintenance")
                .contentType(APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.readOnly").value(true))
        .andExpect(jsonPath("$.data.message").value("DB 灰度中"))
        .andExpect(jsonPath("$.data.affectedServices[0]").value("job-schedule"))
        .andExpect(jsonPath("$.data.affectedServices[1]").value("file-download"));
    // holder 中持有的状态已替换
    MaintenanceState current = stateHolder.current();
    assertThat(current.enabled()).isTrue();
    assertThat(current.readOnly()).isTrue();
    assertThat(current.message()).isEqualTo("DB 灰度中");
    assertThat(current.affectedServices()).containsExactly("job-schedule", "file-download");
  }

  @Test
  void putShouldRestoreToDisabledOnEnabledFalse() throws Exception {
    // 先开
    stateHolder.update(
        new MaintenanceState(
            true, false, "x", Instant.parse("2026-05-20T16:00:00Z"), List.of("a")));
    // 再关
    mockMvc
        .perform(
            put("/api/console/admin/system/maintenance")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));
    assertThat(stateHolder.current().enabled()).isFalse();
  }

  @Test
  void putShouldNormalizeNullAffectedServicesToEmptyList() throws Exception {
    mockMvc
        .perform(
            put("/api/console/admin/system/maintenance")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.affectedServices").isArray())
        .andExpect(jsonPath("$.data.affectedServices.length()").value(0));
  }
}
