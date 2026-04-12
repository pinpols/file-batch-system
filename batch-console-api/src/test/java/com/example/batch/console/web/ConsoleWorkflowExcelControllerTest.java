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
import com.example.batch.console.application.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleWorkflowExcelController;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeExcelRowResponse;
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

class ConsoleWorkflowExcelControllerTest {

  private final ConsoleWorkflowExcelApplicationService excelService =
      mock(ConsoleWorkflowExcelApplicationService.class);
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
                new ConsoleWorkflowExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldExportUploadPreviewAndApplyWorkflowExcel() throws Exception {
    byte[] exportBytes = "workflow-excel".getBytes(StandardCharsets.UTF_8);
    when(excelService.exportWorkflowExcel(any()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
    when(excelService.upload(any()))
        .thenReturn(new ConsoleWorkflowExcelUploadResponse("token-1", "workflow.xlsx", 1, 1, 1, 3));
    when(excelService.preview(anyString()))
        .thenReturn(
            new ConsoleWorkflowExcelPreviewResponse(
                "token-1",
                "workflow.xlsx",
                1,
                1,
                1,
                3,
                3,
                0,
                List.of(
                    new ConsoleWorkflowDefinitionExcelRowResponse(
                        "t1", "WF1", "Workflow 1", "DAG", 1, true, "desc")),
                List.of(
                    new ConsoleWorkflowNodeExcelRowResponse(
                        "t1", "WF1", 1, "NODE1", "Node 1", "TASK", "JOB1", "PIPE1", "WG1", "W1", 1,
                        "FIXED", 3, 30, "{}", true)),
                List.of(
                    new ConsoleWorkflowEdgeExcelRowResponse(
                        "t1", "WF1", 1, "NODE1", "NODE2", "SUCCESS", "${ok}", true)),
                List.of(
                    new ConsoleWorkflowExcelRowIssueResponse(
                        "workflow_definition", 2, "WF1#1", "WF1", 1, List.of("warn")))));
    when(excelService.apply(anyString(), any()))
        .thenReturn(
            new ConsoleWorkflowExcelApplyResponse("token-1", "t1", 1, 1, 1, 1, 0, 1, 0, 1, 0));

    mockMvc
        .perform(get("/api/console/config/workflows/excel/export").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(exportBytes));

    mockMvc
        .perform(
            multipart("/api/console/config/workflows/excel/upload")
                .file(
                    new MockMultipartFile(
                        "file",
                        "workflow.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {4, 5, 6})))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.definitionRows").value(1));

    mockMvc
        .perform(get("/api/console/config/workflows/excel/preview/token-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(3))
        .andExpect(jsonPath("$.data.definitions[0].workflowCode").value("WF1"));

    mockMvc
        .perform(
            post("/api/console/config/workflows/excel/apply/token-1")
                .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":\"bulk\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.insertedDefinitions").value(1))
        .andExpect(jsonPath("$.data.insertedEdges").value(1));
  }
}
