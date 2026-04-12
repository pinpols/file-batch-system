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
import com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleJobDefinitionExcelController;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelRowResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelUploadResponse;
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

class ConsoleJobDefinitionExcelControllerTest {

  private final ConsoleJobDefinitionExcelApplicationService excelService =
      mock(ConsoleJobDefinitionExcelApplicationService.class);
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
                new ConsoleJobDefinitionExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldExportUploadPreviewAndApplyJobDefinitionExcel() throws Exception {
    byte[] exportBytes = "job-definition-excel".getBytes(StandardCharsets.UTF_8);
    when(excelService.exportJobDefinitions(any()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
    when(excelService.upload(any()))
        .thenReturn(new ConsoleJobDefinitionExcelUploadResponse("token-1", "job.xlsx", 1));
    when(excelService.preview(anyString()))
        .thenReturn(
            new ConsoleJobDefinitionExcelPreviewResponse(
                "token-1",
                "job.xlsx",
                1,
                1,
                0,
                List.of(
                    new ConsoleJobDefinitionExcelRowResponse(
                        "t1",
                        "JOB1",
                        "Job 1",
                        "GENERAL",
                        "default",
                        "wg1",
                        "CRON",
                        "0 0 * * *",
                        "cal1",
                        "window1",
                        "FIXED",
                        3,
                        30,
                        "NONE",
                        "handler",
                        "{}",
                        "{}",
                        true,
                        "desc")),
                List.of(
                    new ConsoleJobDefinitionExcelRowIssueResponse(
                        "job_definition", 2, "JOB1", "JOB1", List.of("warn")))));
    when(excelService.apply(anyString(), any()))
        .thenReturn(new ConsoleJobDefinitionExcelApplyResponse("token-1", "t1", 1, 1));

    mockMvc
        .perform(get("/api/console/config/job-definitions/excel/export").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(exportBytes));

    mockMvc
        .perform(
            multipart("/api/console/config/job-definitions/excel/upload")
                .file(
                    new MockMultipartFile(
                        "file",
                        "job.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {1, 2, 3})))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rowCount").value(1));

    mockMvc
        .perform(get("/api/console/config/job-definitions/excel/preview/token-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(1))
        .andExpect(jsonPath("$.data.rows[0].jobCode").value("JOB1"));

    mockMvc
        .perform(
            post("/api/console/config/job-definitions/excel/apply/token-1")
                .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":\"bulk\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.appliedRows").value(1))
        .andExpect(jsonPath("$.data.updatedRows").value(1));
  }
}
