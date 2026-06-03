package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P0: ConsoleForensicController (admin-only 取证导出)。 */
class ConsoleForensicControllerTest {

  private final ConsoleOrchestratorProxyService proxy = mock(ConsoleOrchestratorProxyService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleForensicController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  // @Idempotent 走 WebMvcConfigurer 全局拦截器,standalone MockMvc 不装;
  // Idempotency-Key 缺失场景由 ConsoleIdempotencyInterceptorTest 专项覆盖

  @Test
  void requestExportShouldRejectInvalidTenantIdFormat() throws Exception {
    mockMvc
        .perform(
            post("/api/console/forensic/export")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"../escape","bizDateFrom":"2026-05-19","bizDateTo":"2026-05-19","requestedBy":"admin"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requestExportShouldPassThroughToProxy() throws Exception {
    when(proxy.requestForensicExport(anyString(), any(), any(), any(), anyString(), anyString()))
        .thenReturn(Map.of("exportId", "fx-001"));
    mockMvc
        .perform(
            post("/api/console/forensic/export")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","bizDateFrom":"2026-05-19","bizDateTo":"2026-05-19",
                     "jobCodes":["JOB_A"],"exportFormat":"BUNDLE","requestedBy":"admin"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.exportId").value("fx-001"));
    verify(proxy).requestForensicExport(eq("t1"), any(), any(), any(), eq("BUNDLE"), eq("admin"));
  }

  @Test
  void downloadShouldReturnZipBytesWithAttachmentHeader() throws Exception {
    byte[] payload = new byte[] {1, 2, 3};
    when(proxy.downloadForensicExport("t1", "fx-001")).thenReturn(payload);
    mockMvc
        .perform(get("/api/console/forensic/export/fx-001/download").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"fx-001.zip\""))
        .andExpect(content().bytes(payload));
    assertThat(payload).hasSize(3);
  }
}
