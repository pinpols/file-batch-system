package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.BatchMqTopicsProperties;
import io.github.pinpols.batch.orchestrator.config.MqRoutingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchTopicResolverTest {

  private BatchMqTopicsProperties topics;
  private MqRoutingProperties routing;
  private BatchTopicResolver resolver;

  @BeforeEach
  void setUp() {
    topics = new BatchMqTopicsProperties();
    routing = new MqRoutingProperties();
    resolver = new BatchTopicResolver(topics, routing);
  }

  @Test
  void unsupportedWorkerTypeReturnsNull() {
    assertThat(resolver.resolve("UNKNOWN", null)).isNull();
  }

  @Test
  void singleModeReturnsBaseTopicUnchanged() {
    routing.setMode(MqRoutingProperties.Mode.SINGLE);
    String topic = resolver.resolve("IMPORT", message("t1", "HIGH"));
    assertThat(topic).isEqualTo(topics.getImportDispatch());
  }

  @Test
  void tenantModeAppendsTenantSuffix() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("EXPORT", message("ta", "NORMAL"));
    assertThat(topic).isEqualTo(topics.getExportDispatch() + ".ta");
  }

  @Test
  void tenantModeFallsBackToBaseWhenTenantMissing() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("EXPORT", message(null, "NORMAL"));
    assertThat(topic).isEqualTo(topics.getExportDispatch());
  }

  @Test
  void priorityModeAppendsLowercaseBand() {
    routing.setMode(MqRoutingProperties.Mode.PRIORITY);
    String topic = resolver.resolve("DISPATCH", message("t1", "HIGH"));
    assertThat(topic).isEqualTo(topics.getDispatchDispatch() + ".high");
  }

  @Test
  void priorityModeFallsBackToBaseWhenBandMissing() {
    routing.setMode(MqRoutingProperties.Mode.PRIORITY);
    String topic = resolver.resolve("DISPATCH", message("t1", null));
    assertThat(topic).isEqualTo(topics.getDispatchDispatch());
  }

  @Test
  void illegalCharsInTenantAreSanitized() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("IMPORT", message("ta:1$bad", "HIGH"));
    assertThat(topic).endsWith(".ta_1_bad");
  }

  private static TaskDispatchMessage message(String tenantId, String priorityBand) {
    return new TaskDispatchMessage(
        "v2",
        tenantId,
        1L,
        2L,
        3L,
        "INST-1",
        "JOB",
        "IMPORT",
        null,
        priorityBand,
        "trace-1",
        "idem-1",
        BatchDateTimeSupport.utcNow(),
        null);
  }
}
