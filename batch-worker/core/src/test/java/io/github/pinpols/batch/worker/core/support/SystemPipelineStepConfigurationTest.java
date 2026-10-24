package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Job 参数只能覆盖固定 System Pipeline 阶段的业务参数")
class SystemPipelineStepConfigurationTest {

  private static final List<PipelineStepTemplate> TEMPLATES = List.of(
      PipelineStepTemplate.builder()
          .stepCode("RECEIVE")
          .stepName("Receive")
          .stageCode("RECEIVE")
          .stepOrder(1)
          .implCode("ReceiveStep")
          .stepParams(Map.of("source", "default"))
          .enabled(true)
          .build(),
      PipelineStepTemplate.builder()
          .stepCode("LOAD")
          .stepName("Load")
          .stageCode("LOAD")
          .stepOrder(2)
          .implCode("LoadStep")
          .enabled(true)
          .build());

  @Test
  @DisplayName("按阶段合并 Job 业务参数但不改变阶段、顺序或实现")
  void shouldOverlaysBusinessParamsWithoutChangingSystemStructure() {
    var configured = SystemPipelineStepConfiguration.apply(
        TEMPLATES,
        Map.of(
            "RECEIVE",
            Map.of("source", "job", "encoding", "UTF-8"),
            "LOAD",
            Map.of("mode", "UPSERT")));

    assertThat(configured).extracting("stageCode").containsExactly("RECEIVE", "LOAD");
    assertThat(configured).extracting("stepOrder").containsExactly(1, 2);
    assertThat(configured).extracting("implCode").containsExactly("ReceiveStep", "LoadStep");
    assertThat(configured.getFirst().stepParams())
        .containsEntry("source", "job")
        .containsEntry("encoding", "UTF-8");
    assertThat(configured.get(1).stepParams()).containsEntry("mode", "UPSERT");
  }

  @Test
  @DisplayName("未知阶段配置失败关闭，避免拼错键后静默丢失参数")
  void shouldRejectsUnknownStage() {
    assertThatThrownBy(() -> SystemPipelineStepConfiguration.apply(
            TEMPLATES, Map.of("RECIEVE", Map.of("source", "file"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RECIEVE");
  }

  @Test
  @DisplayName("阶段配置必须为对象而不是标量")
  void shouldRejectsNonObjectStageParameters() {
    assertThatThrownBy(
            () -> SystemPipelineStepConfiguration.apply(TEMPLATES, Map.of("RECEIVE", "file.csv")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RECEIVE");
  }

  @Test
  @DisplayName("Job 参数不能覆盖系统 Pipeline 的阶段跳转和终止控制")
  void shouldRejectsControlFlowOverrides() {
    assertThatThrownBy(() -> SystemPipelineStepConfiguration.apply(
            TEMPLATES, Map.of("RECEIVE", Map.of("onSuccessNextStageCode", "END"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("onSuccessNextStageCode");
  }
}
