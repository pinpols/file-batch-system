package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.console.domain.observability.application.ConsoleReportExcelApplicationService;
import com.example.batch.console.domain.observability.web.ConsoleReportExcelController;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class ConsoleReportExcelControllerTest {

  private final ConsoleReportExcelApplicationService reportService =
      mock(ConsoleReportExcelApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleReportExcelController(reportService)).build();
  }

  @Test
  void shouldExportConfigReleasesAndSchedulerSnapshotExcel() throws Exception {
    byte[] configBytes = "config-releases".getBytes(StandardCharsets.UTF_8);
    byte[] snapshotBytes = "scheduler-snapshot".getBytes(StandardCharsets.UTF_8);
    // R2-P1-9: 返回类型已切到 StreamingResponseBody（lambda 流式写入响应流）
    StreamingResponseBody configBody = out -> out.write(configBytes);
    StreamingResponseBody snapshotBody = out -> out.write(snapshotBytes);
    when(reportService.exportConfigReleases(any())).thenReturn(ResponseEntity.ok(configBody));
    when(reportService.exportSchedulerSnapshot(anyString()))
        .thenReturn(ResponseEntity.ok(snapshotBody));

    // R2-P1-9: StreamingResponseBody 走 Spring async dispatch，MockMvc 需要先抓 async result
    // 再走 asyncDispatch 才能渲染响应体；否则 content() 为空。
    var configResult =
        mockMvc
            .perform(get("/api/console/reports/excel/config-releases").param("tenantId", "t1"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(configResult))
        .andExpect(status().isOk())
        .andExpect(content().bytes(configBytes));

    var snapshotResult =
        mockMvc
            .perform(get("/api/console/reports/excel/scheduler-snapshot").param("tenantId", "t1"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(snapshotResult))
        .andExpect(status().isOk())
        .andExpect(content().bytes(snapshotBytes));
  }
}
