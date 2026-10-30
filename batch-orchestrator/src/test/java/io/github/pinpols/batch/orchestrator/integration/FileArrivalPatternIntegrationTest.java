package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeStatus;
import io.github.pinpols.batch.orchestrator.infrastructure.sensor.FileArrivalSensorPolicy;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("文件到达传感器匹配规则与数据库语义")
class FileArrivalPatternIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private FileArrivalSensorPolicy policy;

  @Autowired
  private JdbcTemplate jdbc;

  @ParameterizedTest
  @CsvSource({
    "invoice_2026*.csv, invoiceX2026x.csv, invoice_2026x.csv",
    "amount%2026*.csv, amountX2026x.csv, amount%2026x.csv",
    "batch\\2026*.csv, batch2026x.csv, batch\\2026x.csv",
    "warn!2026*.csv, warn2026x.csv, warn!2026x.csv"
  })
  @DisplayName("仅星号为通配符, 下划线百分号与反斜线按字面匹配")
  void shouldMatchOnlyLiteralPatternCharacters(String pattern, String decoy, String matching) {
    String tenant = "sensor-pattern-" + System.nanoTime();
    SensorContext context = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", pattern, "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    seed(tenant, decoy);
    var first = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(first.status()).isEqualTo(SensorProbeStatus.NOT_YET);
    seed(tenant, matching);
    var second = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(second.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(second.output()).containsEntry("fileName", matching);
  }

  private void seed(String tenant, String name) {
    String insertFileSql = """
        insert into batch.file_record (tenant_id,file_category,file_name,file_format_type,storage_type,
          storage_path,source_type,file_status)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        """;
    jdbc.update(insertFileSql, tenant, name, tenant + "/" + name);
  }
}
