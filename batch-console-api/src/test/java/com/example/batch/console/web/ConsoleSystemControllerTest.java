package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.config.ConsoleMaintenanceProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleSystemController 维护状态查询(读 MaintenanceStateHolder)+ Cron 预览(Quartz 解析 + 时区)。 */
class ConsoleSystemControllerTest {

  private final BatchTimezoneProvider timezoneProvider = mock(BatchTimezoneProvider.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MaintenanceStateHolder stateHolder;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(timezoneProvider.defaultZone()).thenReturn(ZoneId.of("Asia/Shanghai"));

    ConsoleMaintenanceProperties props = new ConsoleMaintenanceProperties();
    stateHolder = new MaintenanceStateHolder(props);
    ReflectionTestUtils.invokeMethod(stateHolder, "initFromProperties");

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleSystemController(stateHolder, timezoneProvider))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void maintenanceStatusShouldReturnDisabledByDefault() throws Exception {
    mockMvc
        .perform(get("/api/console/system/maintenance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.readOnly").value(false));
  }

  @Test
  void maintenanceStatusShouldReflectHolderState() throws Exception {
    stateHolder.update(
        new MaintenanceState(
            true, true, "DB 灰度中", Instant.parse("2026-05-20T15:00:00Z"), List.of("job-schedule")));
    mockMvc
        .perform(get("/api/console/system/maintenance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.readOnly").value(true))
        .andExpect(jsonPath("$.data.message").value("DB 灰度中"))
        .andExpect(jsonPath("$.data.affectedServices[0]").value("job-schedule"));
  }

  @Test
  void cronPreviewShouldReturnNextFireTimesForValidExpression() throws Exception {
    mockMvc
        .perform(get("/api/console/system/cron-preview").param("expr", "0 0 12 * * ?"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valid").value(true))
        .andExpect(jsonPath("$.data.expr").value("0 0 12 * * ?"))
        .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.data.nextRuns.length()").value(3));
  }

  @Test
  void cronPreviewShouldRejectInvalidExpressionWithValidFalse() throws Exception {
    // 非法 Quartz 表达式 → valid=false,error 非空,nextRuns 空 list,不抛 500
    mockMvc
        .perform(get("/api/console/system/cron-preview").param("expr", "not a cron"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valid").value(false))
        .andExpect(jsonPath("$.data.error").isNotEmpty());
  }

  @Test
  void cronPreviewShouldRejectBlankExpression() throws Exception {
    mockMvc
        .perform(get("/api/console/system/cron-preview").param("expr", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
  }

  @Test
  void cronPreviewShouldCapCountToMax() throws Exception {
    mockMvc
        .perform(
            get("/api/console/system/cron-preview")
                .param("expr", "0 0 12 * * ?")
                .param("count", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.nextRuns.length()").value(20));
  }
}
