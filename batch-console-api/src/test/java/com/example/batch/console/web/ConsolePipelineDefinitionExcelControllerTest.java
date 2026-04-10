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
import com.example.batch.console.application.ConsolePipelineDefinitionExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsolePipelineDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.ConsolePipelineDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.ConsolePipelineDefinitionExcelUploadResponse;
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

class ConsolePipelineDefinitionExcelControllerTest {

    private final ConsolePipelineDefinitionExcelApplicationService excelService = mock(ConsolePipelineDefinitionExcelApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ConsolePipelineDefinitionExcelController(excelService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldExportTemplateUploadPreviewWorkbookAndApply() throws Exception {
        byte[] exportBytes = "pipeline-export".getBytes(StandardCharsets.UTF_8);
        byte[] templateBytes = "pipeline-template".getBytes(StandardCharsets.UTF_8);
        byte[] workbookBytes = "pipeline-preview".getBytes(StandardCharsets.UTF_8);
        when(excelService.exportPipelineDefinitions(anyString(), any(), any(), any())).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
        when(excelService.downloadTemplate()).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(templateBytes))));
        when(excelService.upload(any())).thenReturn(new ConsolePipelineDefinitionExcelUploadResponse("token-1", "pipeline.xlsx", 1, 2));
        when(excelService.preview(anyString())).thenReturn(new ConsolePipelineDefinitionExcelPreviewResponse(
                "token-1", "pipeline.xlsx", 1, 1, 0, 2, 2, 0, List.of(), List.of()
        ));
        when(excelService.downloadPreviewWorkbook(anyString())).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(workbookBytes))));
        when(excelService.apply(anyString(), any())).thenReturn(new ConsolePipelineDefinitionExcelApplyResponse("token-1", "t1", 1, 1, 0, 2));

        mockMvc.perform(get("/api/console/config/pipeline-definitions/excel/export")
                        .param("tenantId", "t1")
                        .param("jobCode", "JOB1")
                        .param("pipelineType", "IMPORT")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(exportBytes));

        mockMvc.perform(get("/api/console/config/pipeline-definitions/excel/template"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(templateBytes));

        mockMvc.perform(multipart("/api/console/config/pipeline-definitions/excel/upload")
                        .file(new MockMultipartFile("file", "pipeline.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pipelineRowCount").value(1))
                .andExpect(jsonPath("$.data.stepRowCount").value(2));

        mockMvc.perform(get("/api/console/config/pipeline-definitions/excel/preview/token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPipelineRows").value(1))
                .andExpect(jsonPath("$.data.totalStepRows").value(2));

        mockMvc.perform(get("/api/console/config/pipeline-definitions/excel/preview/token-1/workbook"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(workbookBytes));

        mockMvc.perform(post("/api/console/config/pipeline-definitions/excel/apply/token-1")
                        .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"bulk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSteps").value(2));
    }
}
