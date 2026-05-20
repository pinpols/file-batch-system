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
import com.example.batch.console.application.config.ConsoleConfigSyncApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.config.ConfigSyncExportRequest;
import com.example.batch.console.web.request.config.ConfigSyncImportRequest;
import com.example.batch.console.web.request.config.ConfigSyncPreviewRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsoleConfigSyncController export/preview/import/logs 透传到 application service。 */
class ConsoleConfigSyncControllerTest {

  private final ConsoleConfigSyncApplicationService service =
      mock(ConsoleConfigSyncApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleConfigSyncController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void exportShouldDelegateWithIdempotencyHeader() throws Exception {
    when(service.export(any(ConfigSyncExportRequest.class))).thenReturn(Map.of("bundleSize", 10));
    mockMvc
        .perform(
            post("/api/console/config/sync/export")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"sourceTenantId\":\"ta\",\"sourceEnv\":\"dev\",\"targetEnv\":\"prod\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bundleSize").value(10));
    verify(service).export(any(ConfigSyncExportRequest.class));
  }

  @Test
  void previewShouldDelegateWithIdempotencyHeader() throws Exception {
    when(service.preview(any(ConfigSyncPreviewRequest.class)))
        .thenReturn(Map.of("willCreate", 5, "willSkip", 2));
    mockMvc
        .perform(
            post("/api/console/config/sync/preview")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"sourceTenantId\":\"ta\",\"tenantId\":\"ta\",\"sourceEnv\":\"dev\",\"targetEnv\":\"prod\"}"))
        .andExpect(status().isOk());
    verify(service).preview(any(ConfigSyncPreviewRequest.class));
  }

  @Test
  void importBundleShouldDelegateWithBundleAndDryRun() throws Exception {
    when(service.importBundle(any(ConfigSyncImportRequest.class)))
        .thenReturn(Map.of("totalTenants", 1, "successTenants", 1));
    mockMvc
        .perform(
            post("/api/console/config/sync/import")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"sourceEnv\":\"dev\",\"targetEnv\":\"prod\","
                        + "\"targetTenantIds\":[\"tb\"],\"dryRun\":true,\"bundle\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalTenants").value(1));
    verify(service).importBundle(any(ConfigSyncImportRequest.class));
  }

  @Test
  void logsShouldPassTenantAndLimit() throws Exception {
    when(service.logs("ta", 50)).thenReturn(List.of(Map.of("ts", "2026-05-20T10:00:00Z")));
    mockMvc
        .perform(get("/api/console/config/sync/logs").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).logs("ta", 50);
  }
}
