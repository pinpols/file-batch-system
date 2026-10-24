package io.github.pinpols.batch.console.domain.audit.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 用例原始输入：附件快照、可选字段与敏感数据脱敏")
class ConsoleAiInputsTest {

  @Test
  @DisplayName("开始轮次输入冻结附件列表，调用方不能在事务处理中改变身份")
  void shouldSnapshotTurnAttachmentIds_whenCallerMutatesOriginal() {
    UUID attachmentId = UUID.randomUUID();
    List<UUID> requestedIds = new ArrayList<>(List.of(attachmentId));
    ConsoleAiTurnStartInput input = ConsoleAiTurnStartInput.builder()
        .attachmentIds(requestedIds)
        .build();

    requestedIds.clear();

    assertThat(input.attachmentIds()).containsExactly(attachmentId);
    assertThatThrownBy(() -> input.attachmentIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("附件绑定输入冻结列表，保留原验证对空元素的处理权")
  void shouldSnapshotBindingIds_whenOriginalContainsNull() {
    UUID attachmentId = UUID.randomUUID();
    List<UUID> requestedIds = new ArrayList<>();
    requestedIds.add(attachmentId);
    requestedIds.add(null);
    ConsoleAiAttachmentBindingInput input = ConsoleAiAttachmentBindingInput.builder()
        .ids(requestedIds)
        .build();

    requestedIds.clear();

    assertThat(input.ids()).containsExactly(attachmentId, null);
    assertThatThrownBy(() -> input.ids().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("可选输入的空值保持原样，由服务在原校验阶段处理")
  void shouldPreserveNullableRawInputs_whenOptionalFieldsAreOmitted() {
    ConsoleAiTurnStartInput turn = ConsoleAiTurnStartInput.builder().build();
    ConsoleAiAttachmentBindingInput binding = ConsoleAiAttachmentBindingInput.builder().build();

    assertThat(turn.requestedConversationId()).isNull();
    assertThat(turn.clientTurnId()).isNull();
    assertThat(turn.attachmentIds()).isNull();
    assertThat(turn.prompt()).isNull();
    assertThat(binding.ids()).isNull();
  }

  @Test
  @DisplayName("用例输入转为日志文本时不暴露提示词、所有者或附件")
  void shouldRedactBothInputTypes_whenConvertedToText() {
    UUID attachmentId = UUID.randomUUID();
    ConsoleAiTurnStartInput turn = ConsoleAiTurnStartInput.builder()
        .ownerUserId("private-owner")
        .prompt("private-prompt")
        .attachmentIds(List.of(attachmentId))
        .build();
    ConsoleAiAttachmentBindingInput binding = ConsoleAiAttachmentBindingInput.builder()
        .ownerUserId("private-owner")
        .ids(List.of(attachmentId))
        .build();

    assertThat(turn.toString()).isEqualTo("ConsoleAiTurnStartInput[redacted]");
    assertThat(binding.toString()).isEqualTo("ConsoleAiAttachmentBindingInput[redacted]");
  }
}
