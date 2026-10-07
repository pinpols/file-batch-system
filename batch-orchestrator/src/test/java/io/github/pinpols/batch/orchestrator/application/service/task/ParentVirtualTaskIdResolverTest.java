package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("父虚拟任务标识解析器: 有效参数提取与非法输入忽略口径")
class ParentVirtualTaskIdResolverTest {

  @Test
  @DisplayName("有效参数中存在正整数标识时解析出该标识")
  void shouldResolveId_whenEffectiveParamsContainPositiveId() {
    assertThat(ParentVirtualTaskIdResolver.resolve(
            "{\"effectiveParams\":{\"_parentVirtualTaskId\":42}}"))
        .isEqualTo(42L);
  }

  @Test
  @DisplayName("参数缺失, 载荷为空或标识非正时返回空")
  void shouldReturnNull_whenIdMissingOrNotPositive() {
    assertThat(ParentVirtualTaskIdResolver.resolve(null)).isNull();
    assertThat(ParentVirtualTaskIdResolver.resolve("{}")).isNull();
    assertThat(ParentVirtualTaskIdResolver.resolve(
            "{\"effectiveParams\":{\"_parentVirtualTaskId\":0}}"))
        .isNull();
  }

  @Test
  @DisplayName("载荷格式非法时返回空而不抛异常")
  void shouldReturnNull_whenPayloadMalformed() {
    assertThat(ParentVirtualTaskIdResolver.resolve("not-json")).isNull();
  }
}
