package io.github.pinpols.batch.console.domain.observability.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.console.domain.observability.application.ConsoleReportExcelApplicationService;
import io.github.pinpols.batch.console.application.contract.response.ExcelDownload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("报表导出接口:配置发布与调度快照的流式导出")
class ConsoleReportExcelControllerTest {

  @Mock
  private ConsoleReportExcelApplicationService reportService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleReportExcelController(reportService))
        .build();
  }

  @Test
  @DisplayName("导出接口:配置发布与调度快照均以流式响应返回字节内容")
  void shouldExportConfigReleasesAndSchedulerSnapshotExcel() throws Exception {
    byte[] configBytes = "config-releases".getBytes(StandardCharsets.UTF_8);
    byte[] snapshotBytes = "scheduler-snapshot".getBytes(StandardCharsets.UTF_8);
    ExcelDownload configBody = new ExcelDownload("config.xlsx", out -> out.write(configBytes));
    ExcelDownload snapshotBody = new ExcelDownload("snapshot.xlsx", out -> out.write(snapshotBytes));
    when(reportService.exportConfigReleases(any())).thenReturn(configBody);
    when(reportService.exportSchedulerSnapshot(anyString()))
        .thenReturn(snapshotBody);

    // R2-P1-9: StreamingResponseBody 走 Spring async dispatch，MockMvc 需要先抓 async result
    // 再走 asyncDispatch 才能渲染响应体；否则 content() 为空。
    var configResult = mockMvc
        .perform(get("/api/console/reports/excel/config-releases").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc
        .perform(asyncDispatch(configResult))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"config.xlsx\""))
        .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(content().bytes(configBytes));

    var snapshotResult = mockMvc
        .perform(get("/api/console/reports/excel/scheduler-snapshot").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc
        .perform(asyncDispatch(snapshotResult))
        .andExpect(status().isOk())
        .andExpect(content().bytes(snapshotBytes));
  }
}
