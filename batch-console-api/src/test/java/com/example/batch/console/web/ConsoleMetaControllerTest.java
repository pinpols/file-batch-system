package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.rbac.service.ConsoleMetaQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.domain.rbac.web.response.ConsoleMetaEnumItem;
import com.example.batch.console.domain.rbac.web.response.ConsoleMetaOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleMetaController 8 元数据端点透传 + 新增的 pipeline-stages / step-impls。 */
class ConsoleMetaControllerTest {

  private final ConsoleMetaQueryService service = mock(ConsoleMetaQueryService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleMetaController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void enumsShouldReturnMap() throws Exception {
    when(service.enums())
        .thenReturn(Map.of("scheduleType", List.of(new ConsoleMetaEnumItem("CRON", "CRON"))));
    mockMvc
        .perform(get("/api/console/meta/enums"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.scheduleType[0].code").value("CRON"));
  }

  @Test
  void queuesAndCalendarsAndWindowsAndWorkerGroupsAndBizTypesShouldPassTenantId() throws Exception {
    List<ConsoleMetaOption> opt = List.of(new ConsoleMetaOption("c1", "C1"));
    when(service.queues("ta")).thenReturn(opt);
    when(service.calendars("ta")).thenReturn(opt);
    when(service.windows("ta")).thenReturn(opt);
    when(service.workerGroups("ta")).thenReturn(opt);
    when(service.bizTypes("ta")).thenReturn(opt);
    mockMvc
        .perform(get("/api/console/meta/queues").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/console/meta/calendars").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/console/meta/windows").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/console/meta/worker-groups").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/console/meta/biz-types").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).queues("ta");
    verify(service).calendars("ta");
    verify(service).windows("ta");
    verify(service).workerGroups("ta");
    verify(service).bizTypes("ta");
  }

  @Test
  void pipelineStagesShouldReturnPerTypeMap() throws Exception {
    when(service.pipelineStages())
        .thenReturn(Map.of("IMPORT", List.of("PARSE", "VALIDATE", "LOAD")));
    mockMvc
        .perform(get("/api/console/meta/pipeline-stages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.IMPORT[0]").value("PARSE"));
  }

  @Test
  void stepImplsShouldPassModuleFilter() throws Exception {
    when(service.stepImpls("IMPORT")).thenReturn(List.of("jdbcMappedImport", "csvParse"));
    mockMvc
        .perform(get("/api/console/meta/step-impls").param("module", "IMPORT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value("jdbcMappedImport"));
    verify(service).stepImpls("IMPORT");
  }

  @Test
  void stepImplsWithoutModuleShouldReturnAll() throws Exception {
    when(service.stepImpls(null)).thenReturn(List.of("any"));
    mockMvc.perform(get("/api/console/meta/step-impls")).andExpect(status().isOk());
    verify(service).stepImpls(null);
  }
}
