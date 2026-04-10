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
import com.example.batch.console.application.ConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelUploadResponse;
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

class ConsoleBatchWindowExcelControllerTest {

    private final ConsoleBatchWindowExcelApplicationService excelService = mock(ConsoleBatchWindowExcelApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleBatchWindowExcelController(excelService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldExportTemplateUploadPreviewWorkbookAndApply() throws Exception {
        byte[] exportBytes = "batch-window-export".getBytes(StandardCharsets.UTF_8);
        byte[] templateBytes = "batch-window-template".getBytes(StandardCharsets.UTF_8);
        byte[] workbookBytes = "batch-window-preview".getBytes(StandardCharsets.UTF_8);
        when(excelService.exportBatchWindows(any())).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
        when(excelService.downloadTemplate()).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(templateBytes))));
        when(excelService.upload(any())).thenReturn(new ConsoleBatchWindowExcelUploadResponse("token-1", "window.xlsx", "batch_window", 1));
        when(excelService.preview(anyString())).thenReturn(new ConsoleBatchWindowExcelPreviewResponse(
                "token-1", "window.xlsx", "batch_window", 1, 1, 0, List.of(), List.of()
        ));
        when(excelService.downloadPreviewWorkbook(anyString())).thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(workbookBytes))));
        when(excelService.apply(anyString(), any())).thenReturn(new ConsoleBatchWindowExcelApplyResponse("token-1", "t1", 1, 1, 0));

        mockMvc.perform(get("/api/console/config/batch-windows/excel/export").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(exportBytes));

        mockMvc.perform(get("/api/console/config/batch-windows/excel/template"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(templateBytes));

        mockMvc.perform(multipart("/api/console/config/batch-windows/excel/upload")
                        .file(new MockMultipartFile("file", "window.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowCount").value(1));

        mockMvc.perform(get("/api/console/config/batch-windows/excel/preview/token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.invalidRows").value(0));

        mockMvc.perform(get("/api/console/config/batch-windows/excel/preview/token-1/workbook"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(workbookBytes));

        mockMvc.perform(post("/api/console/config/batch-windows/excel/apply/token-1")
                        .header(DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"bulk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedRows").value(1));
    }
}
