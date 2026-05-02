package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleFileChannelExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleFileChannelExcelController;
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

class ConsoleFileChannelExcelControllerTest {

  private final ConsoleFileChannelExcelApplicationService excelService =
      mock(ConsoleFileChannelExcelApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleFileChannelExcelController(excelService))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldExportAndDownloadTemplate() throws Exception {
    byte[] exportBytes = "file-channel-export".getBytes(StandardCharsets.UTF_8);
    byte[] templateBytes = "file-channel-template".getBytes(StandardCharsets.UTF_8);
    when(excelService.exportFileChannels(any()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(exportBytes))));
    when(excelService.downloadTemplate())
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(templateBytes))));

    mockMvc
        .perform(get("/api/console/config/file-channels/excel/export").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(exportBytes));

    mockMvc
        .perform(get("/api/console/config/file-channels/excel/template"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(templateBytes));
  }
}
