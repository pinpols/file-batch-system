package com.example.batch.console.web;

import static com.example.batch.common.constants.CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleTenantQuotaPolicyExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleTenantQuotaPolicyExcelController;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelUploadResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleTenantQuotaPolicyExcelControllerTest {

  private final ConsoleTenantQuotaPolicyExcelApplicationService excelService =
      mock(ConsoleTenantQuotaPolicyExcelApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleTenantQuotaPolicyExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldExportTemplateUploadPreviewWorkbookAndApply() throws Exception {
    byte[] exportBytes = "quota-policy-export".getBytes(StandardCharsets.UTF_8);
    byte[] templateBytes = "quota-policy-template".getBytes(StandardCharsets.UTF_8);
    byte[] workbookBytes = "quota-policy-preview".getBytes(StandardCharsets.UTF_8);
    when(excelService.exportQuotaPolicies(any(), any(), any()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
    when(excelService.downloadTemplate())
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(templateBytes))));
    when(excelService.upload(any()))
        .thenReturn(
            new ConsoleTenantQuotaPolicyExcelUploadResponse(
                "token-1", "quota.xlsx", "tenant_quota_policy", 1));
    when(excelService.preview(anyString()))
        .thenReturn(
            new ConsoleTenantQuotaPolicyExcelPreviewResponse(
                "token-1", "quota.xlsx", "tenant_quota_policy", 1, 1, 0, List.of(), List.of()));
    when(excelService.downloadPreviewWorkbook(anyString()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(workbookBytes))));
    when(excelService.apply(anyString(), any()))
        .thenReturn(new ConsoleTenantQuotaPolicyExcelApplyResponse("token-1", "t1", 1, 1, 0));

    mockMvc
        .perform(
            get("/api/console/config/quota-policies/excel/export")
                .param("tenantId", "t1")
                .param("policyCode", "P1")
                .param("enabled", "true"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(exportBytes));

    mockMvc
        .perform(get("/api/console/config/quota-policies/excel/template"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(templateBytes));

    mockMvc
        .perform(
            multipart("/api/console/config/quota-policies/excel/upload")
                .file(
                    new MockMultipartFile(
                        "file",
                        "quota.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {1, 2, 3})))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rowCount").value(1));

    mockMvc
        .perform(get("/api/console/config/quota-policies/excel/preview/token-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(1))
        .andExpect(jsonPath("$.data.validRows").value(1));

    mockMvc
        .perform(get("/api/console/config/quota-policies/excel/preview/token-1/workbook"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(workbookBytes));

    mockMvc
        .perform(
            post("/api/console/config/quota-policies/excel/apply/token-1")
                .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":\"bulk\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.insertedRows").value(1));
  }
}
