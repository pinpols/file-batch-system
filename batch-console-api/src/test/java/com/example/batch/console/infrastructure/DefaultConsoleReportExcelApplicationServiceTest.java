package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.console.application.ConsoleConfigApplicationService;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.response.ConsoleConfigReleaseResponse;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class DefaultConsoleReportExcelApplicationServiceTest {

  @Test
  void shouldExportConfigReleasesWorkbook() throws Exception {
    ConsoleConfigApplicationService configService = mock(ConsoleConfigApplicationService.class);
    ConsoleQueryApplicationService queryService = mock(ConsoleQueryApplicationService.class);
    ConsoleOrchestratorClientProperties properties =
        mock(ConsoleOrchestratorClientProperties.class);
    RestClient.Builder builder = mock(RestClient.Builder.class);
    Environment environment = mock(Environment.class);
    DefaultConsoleReportExcelApplicationService service =
        new DefaultConsoleReportExcelApplicationService(
            configService, queryService, properties, builder, environment);
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
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    "u1",
                    "u1",
                    Instant.now(),
                    Instant.now())));

    ResponseEntity<InputStreamResource> response =
        service.exportConfigReleases(new ConfigReleaseQueryRequest());
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("config_releases");
      Row header = workbook.getSheetAt(0).getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("tenantId");
    }
  }
}
