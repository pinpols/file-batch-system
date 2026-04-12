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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleFileTemplateExcelController;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelUploadResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleFileTemplateExcelControllerTest {

  private final ConsoleFileTemplateExcelApplicationService excelService =
      mock(ConsoleFileTemplateExcelApplicationService.class);
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
                new ConsoleFileTemplateExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldUploadPreviewAndApplyExcelTemplate() throws Exception {
    when(excelService.upload(any()))
        .thenReturn(
            new ConsoleFileTemplateExcelUploadResponse(
                "token-1", "template.xlsx", "file_template_config", 1));
    when(excelService.preview(anyString()))
        .thenReturn(
            new ConsoleFileTemplateExcelPreviewResponse(
                "token-1", "template.xlsx", "file_template_config", 1, 1, 0, List.of(), List.of()));
    when(excelService.apply(anyString(), any()))
        .thenReturn(new ConsoleFileTemplateExcelApplyResponse("token-1", "t1", 1, 1, 0));

    mockMvc
        .perform(
            multipart("/api/console/config/file-templates/excel/upload")
                .file(
                    new MockMultipartFile(
                        "file",
                        "template.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {1, 2, 3})))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rowCount").value(1));

    mockMvc
        .perform(get("/api/console/config/file-templates/excel/preview/token-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(1))
        .andExpect(jsonPath("$.data.validRows").value(1));

    mockMvc
        .perform(
            post("/api/console/config/file-templates/excel/apply/token-1")
                .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":\"bulk\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.insertedRows").value(1));
  }
}
