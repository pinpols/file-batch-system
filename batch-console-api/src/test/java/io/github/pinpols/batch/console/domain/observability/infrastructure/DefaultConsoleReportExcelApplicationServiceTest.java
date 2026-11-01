package io.github.pinpols.batch.console.domain.observability.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApplicationService;
import io.github.pinpols.batch.console.application.contract.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigReleaseResponse;
import io.github.pinpols.batch.console.application.observability.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报表导出服务: 配置发布记录的表格输出结构与表头")
class DefaultConsoleReportExcelApplicationServiceTest {

  @Test
  @DisplayName("导出配置发布返回成功状态, 工作簿包含两个工作表且首表名称与列头符合预期")
  void shouldExportConfigReleasesWorkbook() throws Exception {
    ConsoleConfigApplicationService configService = mock(ConsoleConfigApplicationService.class);
    ConsoleQueryApplicationService queryService = mock(ConsoleQueryApplicationService.class);
    ConsoleOrchestratorPort orchestratorPort = mock(ConsoleOrchestratorPort.class);
    DefaultConsoleReportExcelApplicationService service =
        new DefaultConsoleReportExcelApplicationService(
            configService, queryService, orchestratorPort, dateTimeSupport());
    when(configService.configReleases(any()))
        .thenReturn(List.of(ConsoleConfigReleaseResponse.builder()
            .id(1L)
            .tenantId("t1")
            .configType("FILE")
            .configKey("cfg1")
            .configName("Config 1")
            .configStatus("DRAFT")
            .versionNo(1)
            .configSource("DYNAMIC_DB")
            .activationMode("IMMEDIATE_AFTER_CONFIRMATION")
            .restartRequired(false)
            .applyConfirmationStatus("NOT_RELEASED")
            .grayScopeJson("{}")
            .configPayloadJson("{}")
            .effectiveFromAt(BatchDateTimeSupport.utcNow())
            .effectiveToAt(BatchDateTimeSupport.utcNow())
            .createdBy("u1")
            .updatedBy("u1")
            .createdAt(BatchDateTimeSupport.utcNow())
            .updatedAt(BatchDateTimeSupport.utcNow())
            .build()));

    // 应用结果只携带延迟写入器；写出到内存后验证工作簿内容。
    var response = service.exportConfigReleases(new ConfigReleaseQueryRequest());
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    response.writer().writeTo(sink);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sink.toByteArray()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("config_releases");
      Row header = workbook.getSheetAt(0).getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("tenantId");
    }
  }

  @Test
  @DisplayName("导出调度快照和历史时委派到编排端口")
  void shouldDelegateSchedulerSnapshotExports() {
    ConsoleOrchestratorPort orchestratorPort = mock(ConsoleOrchestratorPort.class);
    DefaultConsoleReportExcelApplicationService service =
        new DefaultConsoleReportExcelApplicationService(
            mock(ConsoleConfigApplicationService.class),
            mock(ConsoleQueryApplicationService.class),
            orchestratorPort,
            dateTimeSupport());
    when(orchestratorPort.schedulerSnapshot("tenant-a")).thenReturn(null);
    when(orchestratorPort.schedulerSnapshotHistory("tenant-a", 10)).thenReturn(List.of());

    assertThat(service.exportSchedulerSnapshot("tenant-a").fileName()).endsWith(".xlsx");
    assertThat(service
            .exportSchedulerSnapshotHistory("tenant-a", 10)
            .fileName())
        .endsWith(".xlsx");

    verify(orchestratorPort).schedulerSnapshot("tenant-a");
    verify(orchestratorPort).schedulerSnapshotHistory("tenant-a", 10);
  }

  private static BatchDateTimeSupport dateTimeSupport() {
    return new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }
}
