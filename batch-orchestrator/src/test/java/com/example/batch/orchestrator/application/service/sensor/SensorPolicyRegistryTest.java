package com.example.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.SensorType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensorPolicyRegistryTest {

  @Test
  void resolve_returnsRegisteredPolicy() {
    SensorPolicy p1 = new StubPolicy(SensorType.FILE_ARRIVAL);
    SensorPolicy p2 = new StubPolicy(SensorType.HTTP_POLL);
    SensorPolicyRegistry registry = new SensorPolicyRegistry(List.of(p1, p2));

    assertThat(registry.resolve(SensorType.FILE_ARRIVAL)).isSameAs(p1);
    assertThat(registry.resolve(SensorType.HTTP_POLL)).isSameAs(p2);
    assertThat(registry.resolve(SensorType.KAFKA_OFFSET)).isNull();
  }

  @Test
  void construct_duplicateSensorType_fails() {
    SensorPolicy a = new StubPolicy(SensorType.FILE_ARRIVAL);
    SensorPolicy b = new StubPolicy(SensorType.FILE_ARRIVAL);
    assertThatThrownBy(() -> new SensorPolicyRegistry(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate SensorPolicy");
  }

  private record StubPolicy(SensorType type) implements SensorPolicy {
    @Override
    public SensorProbeResult probe(SensorContext ctx) {
      return SensorProbeResult.notYet();
    }
  }
}
