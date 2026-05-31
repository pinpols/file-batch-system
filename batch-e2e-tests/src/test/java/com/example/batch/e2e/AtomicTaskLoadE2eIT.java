package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eAtomicApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 负载/压力测试(testcontainers 级):并发往 SPI 专属 topic 灌 N 个原子任务,验证 SPI worker 在并发下 全部跑到终态 SUCCESS,无丢任务 /
 * 无卡死,并记录吞吐。
 *
 * <p>压的是真实派发链(orchestrator → Kafka batch.task.dispatch.atomic → SPI worker claim → 执行 → report),
 * 不是单元级 mock。worker 并发度由 batch.worker.max-concurrent-tasks 控制,N 大于并发度以制造排队。
 *
 * <p>注意:本测试验证的是<b>突发不丢任务</b>(全部终态 SUCCESS),不是吞吐基准 —— testcontainers 单 JVM + 单分区 topic + 每任务
 * claim/report HTTP 往返,有效吞吐很低(~1 task/10s 级),与生产无关。 生产吞吐/SLO 基准用 load-tests 的 {@code
 * SpiTaskDispatchSimulation}(Gatling,打真实部署)。
 */
@SpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA})
@Tag("e2e")
class AtomicTaskLoadE2eIT extends AbstractIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(AtomicTaskLoadE2eIT.class);
  private static final String TENANT = "t1";
  private static final int TASK_COUNT = 15;

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void spiWorkerProcessesConcurrentTaskBurstWithoutLoss() {
    List<String> dedupKeys = new ArrayList<>(TASK_COUNT);

    long launchStart = System.nanoTime();
    for (int i = 0; i < TASK_COUNT; i++) {
      LaunchSeed seed =
          E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
              jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);
      dedupKeys.add(seed.dedupKey());

      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "sql");
      params.put("sql", "SELECT 1");

      launchService.launch(
          new LaunchRequest(
              TENANT,
              seed.jobCode(),
              LocalDate.of(2026, 1, 15),
              TriggerType.API,
              seed.requestId(),
              "e2e-tr-spi-load-" + i,
              params));
    }
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    log.info(
        "[spi-load] launched {} tasks in {} ms",
        TASK_COUNT,
        (System.nanoTime() - launchStart) / 1_000_000);

    long drainStart = System.nanoTime();
    await()
        .atMost(Duration.ofSeconds(300))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(succeededCount(dedupKeys)).isEqualTo(TASK_COUNT));

    long drainMillis = (System.nanoTime() - drainStart) / 1_000_000;
    double throughput = TASK_COUNT * 1000.0 / Math.max(1, drainMillis);
    log.info(
        "[spi-load] {} tasks reached SUCCESS in {} ms (~{} tasks/s)",
        TASK_COUNT,
        drainMillis,
        String.format("%.1f", throughput));

    // 无丢任务:终态成功数恰好 == 投放数
    assertThat(succeededCount(dedupKeys)).isEqualTo(TASK_COUNT);
  }

  private int succeededCount(List<String> dedupKeys) {
    String inClause = String.join(",", java.util.Collections.nCopies(dedupKeys.size(), "?"));
    Object[] args = new Object[dedupKeys.size() + 1];
    args[0] = TENANT;
    for (int i = 0; i < dedupKeys.size(); i++) {
      args[i + 1] = dedupKeys.get(i);
    }
    Integer n =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_task t"
                + " join batch.job_instance ji on ji.id = t.job_instance_id"
                + " where ji.tenant_id = ? and ji.dedup_key in ("
                + inClause
                + ") and t.task_status = 'SUCCESS'",
            Integer.class,
            args);
    return n == null ? 0 : n;
  }
}
