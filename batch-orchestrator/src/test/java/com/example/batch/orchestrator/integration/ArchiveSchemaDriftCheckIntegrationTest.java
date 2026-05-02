package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.infrastructure.archive.ArchiveSchemaDriftCheck;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ArchiveSchemaDriftCheck 启动期守护测试 — 模拟"运维给 batch.* 加列但忘了同步 archive.*_archive"。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
class ArchiveSchemaDriftCheckIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ArchiveSchemaDriftCheck check;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    // 把测试加的临时列删干净,防污染其他 IT
    jdbcTemplate.execute("alter table batch.outbox_event drop column if exists drift_test_col");
  }

  @Test
  void noDriftWhenSchemasMatch() {
    // 正常状态(V71 migration 跑完后,所有冷热表 column 一致)— 不抛异常
    check.checkOnStartup();
  }

  @Test
  void driftDetectedWhenHotTableHasExtraColumn() {
    // 模拟:运维给 batch.outbox_event 加了 column,但忘了同步 archive.outbox_event_archive
    jdbcTemplate.execute("alter table batch.outbox_event add column drift_test_col varchar(64)");

    assertThatThrownBy(() -> check.checkOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("archive schema drift detected")
        .hasMessageContaining("Add migration to ALTER archive.*");
  }

  @Test
  void columnsOfReturnsExpectedColumns() {
    // 抽样验证 columnsOf 工具方法行为正确
    var hotCols = check.columnsOf("batch", "outbox_event");
    var coldCols = check.columnsOf("archive", "outbox_event_archive");
    assertThat(hotCols).isNotEmpty();
    assertThat(coldCols).isNotEmpty();
    assertThat(hotCols).containsExactlyInAnyOrderElementsOf(coldCols);
  }
}
