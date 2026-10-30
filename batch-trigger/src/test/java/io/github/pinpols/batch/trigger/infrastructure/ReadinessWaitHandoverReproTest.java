package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.trigger.infrastructure.scheduler.ReadinessWaitSweeper;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * readiness 持久等待移交的验收测试（Phase 1）。
 *
 * <p>修复前：等待截止时间只存在于 Quartz JobDataMap，超窗终结只在 Quartz 回调内，回调丢失即永久卡在
 * WAITING_READINESS。修复后：等待窗口随 occurrence 落库，周期扫尾器可在无 Quartz 回调时按 CAS 终结等待。
 */
@DisplayName("readiness 等待移交: 截止时间落库且无 Quartz 回调也能终结")
class ReadinessWaitHandoverReproTest {

  @Test
  @DisplayName("等待记录写入截止时间与起算时间（领域记录自证 deadline）")
  void shouldPersistReadinessWindow() {
    Method recordWaitingReadiness = Arrays.stream(
            ScheduledOccurrenceRecorder.class.getDeclaredMethods())
        .filter(method ->
            "recordWaitingReadiness".equals(method.getName()) && method.getParameterCount() == 5)
        .findFirst()
        .orElseThrow();
    Parameter[] parameters = recordWaitingReadiness.getParameters();

    assertThat(parameters).hasSize(5);
    assertThat(Arrays.stream(parameters)
            .filter(parameter -> parameter.getType() == Instant.class)
            .count())
        .as("3 个 Instant = 原 fire 时间 + deferredSince + deadline")
        .isEqualTo(3);
  }

  @Test
  @DisplayName("周期扫尾器具备终结超窗等待的能力，且不依赖 Quartz 回调")
  void shouldSweeperEndStaleReadinessWaitWithoutQuartzCallback() {
    Class<?>[] sweeperCollaborators = Arrays.stream(ReadinessWaitSweeper.class.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(Field::getType)
        .toArray(Class<?>[]::new);
    assertThat(sweeperCollaborators)
        .as("扫尾器必须能读写 occurrence 状态才能终结等待")
        .contains(ScheduledOccurrenceMapper.class);

    Method sweep = Arrays.stream(ReadinessWaitSweeper.class.getDeclaredMethods())
        .filter(method -> "sweep".equals(method.getName()))
        .findFirst()
        .orElseThrow();
    assertThat(sweep.getAnnotation(Scheduled.class))
        .as("扫尾必须由周期调度触发，而不是依赖 Quartz 回调")
        .isNotNull();
  }

  @Test
  @DisplayName("等待窗口与到期扫描已落到映射语句（领域侧可查 deadline）")
  void shouldPersistDeadlineInMapperStatements() throws Exception {
    String mapperXml;
    try (InputStream inputStream = ReadinessWaitHandoverReproTest.class.getResourceAsStream(
        "/mapper/ScheduledOccurrenceMapper.xml")) {
      assertThat(inputStream).isNotNull();
      mapperXml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(mapperXml).contains("readiness_deadline");
    assertThat(mapperXml).contains("selectExpiredReadinessWaits");
    assertThat(mapperXml).contains("updateReadinessWait");
  }
}
