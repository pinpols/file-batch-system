package io.github.pinpols.batch.console.domain.workflow.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.workflow.application.PipelineDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.PipelineDefinitionDetailResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Pipeline definition reads remain available; tenant-owned writes are retired. */
@DisplayName("流水线定义控制器行为: 读取保留，租户定义写入退役")
class ConsolePipelineDefinitionControllerBehaviorTest {

  private final PipelineDefinitionService service = mock(PipelineDefinitionService.class);
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
    mockMvc = MockMvcBuilders.standaloneSetup(
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
  @DisplayName("分页查询流水线定义时, 租户与筛选条件原样传给服务并返回成功")
  void shouldPassFilters_whenListingPipelineDefinitions() throws Exception {
    when(service.list("ta", "JOB_A", "IMPORT", true, 1, 20))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/pipeline-definitions")
            .param("tenantId", "ta")
            .param("jobCode", "JOB_A")
            .param("pipelineType", "IMPORT")
            .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).list("ta", "JOB_A", "IMPORT", true, 1, 20);
  }

  @Test
  @DisplayName("按标识查询流水线明细时, 响应返回落库的标识与作业编码")
  void shouldReturnDetail_whenQueryingPipelineById() throws Exception {
    when(service.detail(3L, "ta")).thenReturn(detailFixture(3L, "JOB_A"));
    mockMvc
        .perform(get("/api/console/pipeline-definitions/3").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(3))
        .andExpect(jsonPath("$.data.jobCode").value("JOB_A"));
  }

  @Test
  @DisplayName("新建流水线定义被拒绝并提示按作业类型使用系统流水线")
  void rejectsCreate_whenTenantPipelineDefinitionsAreRetired() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"jobCode\":\"JOB_NEW\",\"pipelineName\":\"p\",\"pipelineType\":\"IMPORT\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    verify(service, org.mockito.Mockito.never()).create(any(PipelineDefinitionSaveRequest.class));
  }

  @Test
  @DisplayName("更新流水线定义被拒绝且不调用写服务")
  void rejectsUpdate_whenTenantPipelineDefinitionsAreRetired() throws Exception {
    mockMvc
        .perform(
            put("/api/console/pipeline-definitions/7")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"jobCode\":\"JOB_U\",\"pipelineName\":\"p\",\"pipelineType\":\"IMPORT\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    verify(service, org.mockito.Mockito.never())
        .update(eq(7L), any(PipelineDefinitionSaveRequest.class));
  }

  @Test
  @DisplayName("启停流水线定义被拒绝且不调用写服务")
  void rejectsToggle_whenTenantPipelineDefinitionsAreRetired() throws Exception {
    mockMvc
        .perform(patch("/api/console/pipeline-definitions/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    verify(service, org.mockito.Mockito.never()).toggle(9L, "ta", true);
  }
}
