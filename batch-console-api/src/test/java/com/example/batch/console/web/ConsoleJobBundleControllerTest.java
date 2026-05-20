package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.job.ConsoleJobBundleApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.job.JobBundleCreateRequest;
import com.example.batch.console.web.request.job.JobBundleImportRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P0: ConsoleJobBundleController create/export/import + strict 语义透传。 */
class ConsoleJobBundleControllerTest {

  private final ConsoleJobBundleApplicationService applicationService =
      mock(ConsoleJobBundleApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleJobBundleController(applicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  // @Idempotent 全局拦截器在 standalone MockMvc 不装;Idempotency 缺失场景见
  // ConsoleIdempotencyInterceptorTest

  @Test
  void createShouldForwardToApplicationService() throws Exception {
    when(applicationService.create(any(JobBundleCreateRequest.class)))
        .thenReturn(Map.of("tenantId", "t1", "result", "ok"));
    mockMvc
        .perform(
            post("/api/console/jobs/bundle/create")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"mode\":\"UPSERT\",\"bundle\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    ArgumentCaptor<JobBundleCreateRequest> captor =
        ArgumentCaptor.forClass(JobBundleCreateRequest.class);
    verify(applicationService).create(captor.capture());
    JobBundleCreateRequest req = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(req.getTenantId()).isEqualTo("t1");
  }

  @Test
  void exportShouldRequireQueryParams() throws Exception {
    mockMvc
        .perform(get("/api/console/jobs/bundle/export").param("tenantId", "t1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exportShouldReturnBundlePayload() throws Exception {
    when(applicationService.exportBundle("t1", "JOB_A"))
        .thenReturn(
            Map.of("tenantId", "t1", "bundle", Map.of("jobDefinitions", java.util.List.of())));
    mockMvc
        .perform(
            get("/api/console/jobs/bundle/export")
                .param("tenantId", "t1")
                .param("jobCode", "JOB_A"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value("t1"));
  }

  @Test
  void importShouldRejectEmptyTargetTenantIds() throws Exception {
    // BE Request 上 targetTenantIds 是 @NotEmpty;空数组 → 400
    mockMvc
        .perform(
            post("/api/console/jobs/bundle/import")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"targetTenantIds\":[],\"bundle\":{}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importShouldForwardToApplicationService() throws Exception {
    when(applicationService.importBundle(any(JobBundleImportRequest.class)))
        .thenReturn(Map.of("tenantId", "t1", "result", Map.of("totalTenants", 2)));
    mockMvc
        .perform(
            post("/api/console/jobs/bundle/import")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"t1\",\"targetTenantIds\":[\"t1\",\"t2\"],\"mode\":\"UPSERT\",\"bundle\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.result.totalTenants").value(2));
    verify(applicationService).importBundle(any(JobBundleImportRequest.class));
  }
}
