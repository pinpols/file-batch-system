package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleResourceQueueExcelController;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleResourceQueueExcelControllerTest {

  private final ConsoleResourceQueueExcelApplicationService excelService =
      mock(ConsoleResourceQueueExcelApplicationService.class);
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
                new ConsoleResourceQueueExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldExportResourceQueues() throws Exception {
    byte[] bytes = "resource-queue-export".getBytes(StandardCharsets.UTF_8);
    when(excelService.exportResourceQueues(any(), any(), any(), any()))
        .thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(bytes))));

    mockMvc
        .perform(
            get("/api/console/config/resource-queues/excel/export")
                .param("tenantId", "t1")
                .param("queueCode", "Q1")
                .param("queueType", "IMPORT")
                .param("enabled", "true"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes));
  }

  @Test
  void shouldDownloadTemplate() throws Exception {
    byte[] bytes = "resource-queue-template".getBytes(StandardCharsets.UTF_8);
    when(excelService.downloadTemplate())
        .thenReturn(ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(bytes))));

    mockMvc
        .perform(get("/api/console/config/resource-queues/excel/template"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes));
  }
}
