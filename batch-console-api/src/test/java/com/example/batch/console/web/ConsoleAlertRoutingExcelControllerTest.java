package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.monitor.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.excel.ConsoleAlertRoutingExcelController;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleAlertRoutingExcelControllerTest {

  private final ConsoleAlertRoutingExcelApplicationService excelService =
      mock(ConsoleAlertRoutingExcelApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(
                new ConsoleAlertRoutingExcelController(excelService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldDownloadTemplate() throws Exception {
    when(excelService.downloadTemplate())
        .thenReturn(
            ResponseEntity.ok()
                .body(new InputStreamResource(new ByteArrayInputStream(new byte[] {1, 2, 3}))));

    mockMvc
        .perform(get("/api/console/config/alert-routings/excel/template"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldExportAlertRoutings() throws Exception {
    when(excelService.exportAlertRoutings(any()))
        .thenReturn(
            ResponseEntity.ok()
                .body(new InputStreamResource(new ByteArrayInputStream(new byte[] {1, 2, 3}))));

    mockMvc
        .perform(get("/api/console/config/alert-routings/excel/export"))
        .andExpect(status().isOk());
  }
}
