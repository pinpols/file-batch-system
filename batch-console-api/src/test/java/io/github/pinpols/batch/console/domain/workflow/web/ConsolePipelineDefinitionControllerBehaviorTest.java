package io.github.pinpols.batch.console.domain.workflow.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.workflow.application.ConsolePipelineDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.workflow.web.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.PipelineDefinitionDetailResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsolePipelineDefinitionController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
class ConsolePipelineDefinitionControllerBehaviorTest {

  private final ConsolePipelineDefinitionApplicationService service =
      mock(ConsolePipelineDefinitionApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsolePipelineDefinitionController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private static PipelineDefinitionDetailResponse detailFixture(long id, String code) {
    return new PipelineDefinitionDetailResponse(
        id, "ta", code, "p1", "IMPORT", "TEST", "import", 1, true, "desc", null, null, List.of());
  }

  @Test
  void listShouldPassFilters() throws Exception {
    when(service.list(eq("ta"), eq("JOB_A"), eq("IMPORT"), eq(true), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(
            get("/api/console/pipeline-definitions")
                .param("tenantId", "ta")
                .param("jobCode", "JOB_A")
                .param("pipelineType", "IMPORT")
                .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "JOB_A", "IMPORT", true, 1, 20);
  }

  @Test
  void detailShouldDelegate() throws Exception {
    when(service.detail(3L, "ta")).thenReturn(detailFixture(3L, "JOB_A"));
    mockMvc
        .perform(get("/api/console/pipeline-definitions/3").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(3))
        .andExpect(jsonPath("$.data.jobCode").value("JOB_A"));
  }

  @Test
  void createShouldReturnPersistedDetail() throws Exception {
    when(service.create(any(PipelineDefinitionSaveRequest.class)))
        .thenReturn(detailFixture(11L, "JOB_NEW"));
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"jobCode\":\"JOB_NEW\",\"pipelineName\":\"p\",\"pipelineType\":\"IMPORT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(11));
  }

  @Test
  void updateShouldPassPathId() throws Exception {
    when(service.update(eq(7L), any(PipelineDefinitionSaveRequest.class)))
        .thenReturn(detailFixture(7L, "JOB_U"));
    mockMvc
        .perform(
            put("/api/console/pipeline-definitions/7")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"jobCode\":\"JOB_U\",\"pipelineName\":\"p\",\"pipelineType\":\"IMPORT\"}"))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(PipelineDefinitionSaveRequest.class));
  }

  @Test
  void toggleShouldDelegate() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions/9/toggle")
                .param("tenantId", "ta")
                .param("enabled", "false"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", false);
  }
}
