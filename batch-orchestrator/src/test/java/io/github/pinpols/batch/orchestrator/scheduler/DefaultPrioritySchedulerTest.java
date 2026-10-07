package io.github.pinpols.batch.orchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.SchedulingPriorityBand;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.DefaultPriorityScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("优先级解析组件,验证优先级的默认值与越界收敛规则以及优先级分档的区间划分")
class DefaultPrioritySchedulerTest {

  private DefaultPriorityScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new DefaultPriorityScheduler();
  }

  // --- resolvePriority ---

  @Test
  @DisplayName("未提供调度请求时,应返回默认优先级")
  void shouldReturnDefaultPriorityFiveWhenRequestIsNull() {
    assertThat(scheduler.resolvePriority(null, null)).isEqualTo(5);
  }

  @Test
  @DisplayName("请求未设置优先级时,应返回默认优先级")
  void shouldReturnDefaultPriorityFiveWhenPriorityIsNull() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setPriority(null);
    assertThat(scheduler.resolvePriority(request, null)).isEqualTo(5);
  }

  @Test
  @DisplayName("优先级低于允许下限时,应收敛到下限值")
  void shouldClampPriorityToOneWhenBelowRange() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setPriority(0);
    assertThat(scheduler.resolvePriority(request, null)).isEqualTo(1);

    request.setPriority(-5);
    assertThat(scheduler.resolvePriority(request, null)).isEqualTo(1);
  }

  @Test
  @DisplayName("优先级高于允许上限时,应收敛到上限值")
  void shouldClampPriorityToNineWhenAboveRange() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setPriority(10);
    assertThat(scheduler.resolvePriority(request, null)).isEqualTo(9);

    request.setPriority(100);
    assertThat(scheduler.resolvePriority(request, null)).isEqualTo(9);
  }

  @Test
  @DisplayName("优先级处于允许区间内时,应原样返回输入值")
  void shouldReturnExactValueWhenWithinValidRange() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    for (int p = 1; p <= 9; p++) {
      request.setPriority(p);
      assertThat(scheduler.resolvePriority(request, null)).isEqualTo(p);
    }
  }

  // --- resolvePriorityBand ---

  @Test
  @DisplayName("优先级位于最高档区间时,应解析为高档位")
  void shouldReturnHighBandForPriorityOneToThree() {
    assertThat(scheduler.resolvePriorityBand(1)).isEqualTo(SchedulingPriorityBand.HIGH.code());
    assertThat(scheduler.resolvePriorityBand(2)).isEqualTo(SchedulingPriorityBand.HIGH.code());
    assertThat(scheduler.resolvePriorityBand(3)).isEqualTo(SchedulingPriorityBand.HIGH.code());
  }

  @Test
  @DisplayName("优先级位于中间档区间时,应解析为中档位")
  void shouldReturnMediumBandForPriorityFourToSix() {
    assertThat(scheduler.resolvePriorityBand(4)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
    assertThat(scheduler.resolvePriorityBand(5)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
    assertThat(scheduler.resolvePriorityBand(6)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
  }

  @Test
  @DisplayName("优先级位于最低档区间时,应解析为低档位")
  void shouldReturnLowBandForPrioritySevenToNine() {
    assertThat(scheduler.resolvePriorityBand(7)).isEqualTo(SchedulingPriorityBand.LOW.code());
    assertThat(scheduler.resolvePriorityBand(8)).isEqualTo(SchedulingPriorityBand.LOW.code());
    assertThat(scheduler.resolvePriorityBand(9)).isEqualTo(SchedulingPriorityBand.LOW.code());
  }

  @Test
  @DisplayName("未提供优先级时,分档应回落为中档位")
  void shouldReturnMediumBandWhenPriorityIsNull() {
    assertThat(scheduler.resolvePriorityBand(null)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
  }
}
