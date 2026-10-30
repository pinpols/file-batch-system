package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("作业依赖规则替换：显式空集合清空，缺失集合在任何写入前拒绝")
@ExtendWith(MockitoExtension.class)
class JobDependencyRuleWriterTest {

  @Mock
  private JobDefinitionMapper mapper;

  private JobDependencyRuleWriter writer;

  @Test
  @DisplayName("缺少规则集合时先拒绝，不删除已有依赖")
  void shouldRejectBeforeDeletingDependencies_whenRulesAreMissing() {
    assertThatNullPointerException().isThrownBy(() -> writer.replace("tenant", "downstream", null));

    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("显式空规则集合仅清空已有依赖")
  void shouldClearDependencies_whenRulesAreExplicitlyEmpty() {
    writer.replace("tenant", "downstream", List.of());

    verify(mapper).deleteDependencies("tenant", "downstream");
    verifyNoMoreInteractions(mapper);
  }

  @Test
  @DisplayName("非空规则集合先删除后写入，并保留业务日对齐方式")
  void shouldReplaceRulesInOrder_whenRulesArePresent() {
    List<JobDependencyRuleRequest> rules =
        List.of(new JobDependencyRuleRequest("upstream", "PREVIOUS_BIZ_DAY"));

    writer.replace("tenant", "downstream", rules);

    InOrder order = inOrder(mapper);
    order.verify(mapper).deleteDependencies("tenant", "downstream");
    order.verify(mapper).insertDependencyRules("tenant", "downstream", rules);
    verifyNoMoreInteractions(mapper);
  }

  @BeforeEach
  void initializeSubject() {
    writer = new JobDependencyRuleWriter(mapper);
  }
}
