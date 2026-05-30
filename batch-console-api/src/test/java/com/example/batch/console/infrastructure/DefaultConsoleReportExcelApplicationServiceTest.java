package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.config.ConsoleConfigApplicationService;
import com.example.batch.console.application.report.ConsoleQueryApplicationService;
import com.example.batch.console.infrastructure.report.DefaultConsoleReportExcelApplicationService;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import java.time.Clock;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class DefaultConsoleReportExcelApplicationServiceTest {

  @Test
  void shouldExportConfigReleasesWorkbook() throws Exception {
    ConsoleConfigApplicationService configService = mock(ConsoleConfigApplicationService.class);
    ConsoleQueryApplicationService queryService = mock(ConsoleQueryApplicationService.class);
    com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient
        orchestratorInternalRestClient =
            mock(com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient.class);
    DefaultConsoleReportExcelApplicationService service =
        new DefaultConsoleReportExcelApplicationService(
            configService, queryService, orchestratorInternalRestClient, dateTimeSupport());
    when(configService.configReleases(any()))
        .thenReturn(
            List.of(
                new ConsoleConfigReleaseResponse(
                    1L,
                    "t1",
                    "FILE",
                    "cfg1",
                    "Config 1",
                    "DRAFT",
                    1,
                    "{}",
                    "{}",
                    BatchDateTimeSupport.utcNow(),
                    BatchDateTimeSupport.utcNow(),
                    null,
                    null,
                    "u1",
                    "u1",
                    BatchDateTimeSupport.utcNow(),
                    BatchDateTimeSupport.utcNow())));

    // R2-P1-9: 返回类型已切到 StreamingResponseBody；通过 lambda 写出到 ByteArrayOutputStream 再校验
    var response = service.exportConfigReleases(new ConfigReleaseQueryRequest());
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    response.getBody().writeTo(sink);
    try (Workbook workbook =
        WorkbookFactory.create(new java.io.ByteArrayInputStream(sink.toByteArray()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("config_releases");
      Row header = workbook.getSheetAt(0).getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("tenantId");
    }
  }

  private static BatchDateTimeSupport dateTimeSupport() {
    return new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }
}
