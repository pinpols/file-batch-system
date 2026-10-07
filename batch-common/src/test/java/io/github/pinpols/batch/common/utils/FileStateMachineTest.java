package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件状态机: 初始状态校验, 合法迁移链判定与非法迁移的错误码")
class FileStateMachineTest {

  @Test
  @DisplayName("初始状态校验: 已接收与已生成允许作为起点")
  void shouldAcceptInitialStatuses() {
    FileStateMachine.assertInitialStatus(FileStatus.RECEIVED.name());
    FileStateMachine.assertInitialStatus(FileStatus.GENERATED.name());
  }

  @Test
  @DisplayName("初始状态校验: 解析中的状态被拒绝并返回状态冲突码")
  void shouldRejectNonInitialStatusesForInitialAssertion() {
    assertThatThrownBy(() -> FileStateMachine.assertInitialStatus(FileStatus.PARSING.name()))
        .isInstanceOf(BizException.class)
        .extracting(BizException.class::cast)
        .extracting(BizException::getCode)
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("状态迁移: 相同状态的自迁移视为合法")
  void shouldAllowSameStatusTransition() {
    FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.RECEIVED.name());
    assertThat(
            FileStateMachine.canTransition(FileStatus.RECEIVED.name(), FileStatus.RECEIVED.name()))
        .isTrue();
  }

  @Test
  @DisplayName("状态迁移: 导入链路逐级推进全部放行")
  void shouldAllowValidImportChain() {
    FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.PARSING.name());
    FileStateMachine.assertTransition(FileStatus.PARSING.name(), FileStatus.PARSED.name());
    FileStateMachine.assertTransition(FileStatus.PARSED.name(), FileStatus.VALIDATED.name());
    FileStateMachine.assertTransition(FileStatus.VALIDATED.name(), FileStatus.LOADED.name());
    FileStateMachine.assertTransition(FileStatus.LOADED.name(), FileStatus.ARCHIVED.name());
  }

  @Test
  @DisplayName("状态迁移: 跨级跳转被拒绝并返回状态冲突码")
  void shouldRejectIllegalTransition() {
    assertThatThrownBy(() ->
            FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.LOADED.name()))
        .isInstanceOf(BizException.class)
        .extracting(BizException.class::cast)
        .extracting(BizException::getCode)
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("可迁移判定: 非法跳转返回否, 不抛异常")
  void shouldReportIllegalTransitionViaCanTransition() {
    assertThat(FileStateMachine.canTransition(FileStatus.RECEIVED.name(), FileStatus.LOADED.name()))
        .isFalse();
  }

  @Test
  @DisplayName("状态迁移: 目标状态为空时返回非法参数码")
  void shouldRejectBlankStatusCode() {
    assertThatThrownBy(() -> FileStateMachine.assertTransition("", FileStatus.RECEIVED.name()))
        .isInstanceOf(BizException.class)
        .extracting(BizException.class::cast)
        .extracting(BizException::getCode)
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }
}
