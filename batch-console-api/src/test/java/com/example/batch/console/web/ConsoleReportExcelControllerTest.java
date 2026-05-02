package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.console.application.report.ConsoleReportExcelApplicationService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    when(reportService.exportConfigReleases(any()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(configBytes))));
    when(reportService.exportSchedulerSnapshot(anyString()))
        .thenReturn(
            ResponseEntity.ok(new InputStreamResource(new ByteArrayInputStream(snapshotBytes))));

    mockMvc
        .perform(get("/api/console/reports/excel/config-releases").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(configBytes));

    mockMvc
        .perform(get("/api/console/reports/excel/scheduler-snapshot").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(snapshotBytes));
  }
}
