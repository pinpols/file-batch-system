package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("安全增量补偿 hook：opt-in off 逐字节不变 / on 才触发 / best-effort 不掩盖原始失败")
class PipelineCompensationHookTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  private PipelineCompensationHook hook(PipelineCompensator... compensators) {
    return new PipelineCompensationHook(runtimeRepository, provider(compensators));
  }

  @Test
  @DisplayName("开关 off（默认）：不进入 COMPENSATING、不调任何 compensator")
  void offByDefault_doesNotCompensate() {
    RecordingCompensator compensator = new RecordingCompensator("IMPORT");
    PipelineCompensationHook hook = hook(compensator);
    Map<String, Object> attributes = attributesWithTemplate(false);

    boolean triggered = hook.runCompensation("t1", "IMPORT", 99L, attributes);

    assertThat(triggered).isFalse();
    assertThat(compensator.calls).isZero();
    verify(runtimeRepository, never()).markPipelineCompensating(any());
  }

  @Test
  @DisplayName("templateConfig 缺失（失败发生在加载模板前）：安全跳过，不补偿")
  void noTemplateConfig_doesNotCompensate() {
    RecordingCompensator compensator = new RecordingCompensator("IMPORT");
    PipelineCompensationHook hook = hook(compensator);

    boolean triggered = hook.runCompensation("t1", "IMPORT", 99L, new LinkedHashMap<>());

    assertThat(triggered).isFalse();
    assertThat(compensator.calls).isZero();
    verify(runtimeRepository, never()).markPipelineCompensating(any());
  }

  @Test
  @DisplayName("开关 on 且注册了 compensator：先 COMPENSATING → 调 compensator → 审计")
  void onWithCompensator_marksCompensatingAndReverses() {
    RecordingCompensator compensator = new RecordingCompensator("IMPORT");
    compensator.result = CompensationResult.reversed(3L, "deleted 3 rows");
    PipelineCompensationHook hook = hook(compensator);
    Map<String, Object> attributes = attributesWithTemplate(true);
    attributes.put(PipelineRuntimeKeys.FILE_ID, 7L);
    when(runtimeRepository.toLong(7L)).thenReturn(7L);

    boolean triggered = hook.runCompensation("t1", "IMPORT", 99L, attributes);

    assertThat(triggered).isTrue();
    assertThat(compensator.calls).isEqualTo(1);
    verify(runtimeRepository).markPipelineCompensating(99L);
    ArgumentCaptor<FileAuditParam> audit = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(runtimeRepository).appendAudit(audit.capture());
    assertThat(audit.getValue().getOperationType()).isEqualTo("PIPELINE_COMPENSATE");
    assertThat(audit.getValue().getOperationResult()).isEqualTo("REVERSED");
  }

  @Test
  @DisplayName("开关 on 但该类型没注册 compensator：不补偿，走原路径")
  void onWithoutMatchingCompensator_doesNotCompensate() {
    RecordingCompensator other = new RecordingCompensator("EXPORT");
    PipelineCompensationHook hook = hook(other);
    Map<String, Object> attributes = attributesWithTemplate(true);

    boolean triggered = hook.runCompensation("t1", "IMPORT", 99L, attributes);

    assertThat(triggered).isFalse();
    assertThat(other.calls).isZero();
    verify(runtimeRepository, never()).markPipelineCompensating(any());
  }

  @Test
  @DisplayName("compensator 内部抛异常：hook 不上抛，仍记审计，原始失败不被掩盖")
  void compensatorThrows_isSwallowed() {
    PipelineCompensator throwing =
        new PipelineCompensator() {
          @Override
          public String pipelineType() {
            return "IMPORT";
          }

          @Override
          public CompensationResult compensate(
              String tenantId, Long pipelineInstanceId, Long fileId, Map<String, Object> attrs) {
            throw new IllegalStateException("boom");
          }
        };
    PipelineCompensationHook hook = hook(throwing);
    Map<String, Object> attributes = attributesWithTemplate(true);

    boolean triggered = hook.runCompensation("t1", "IMPORT", 99L, attributes);

    assertThat(triggered).isTrue();
    verify(runtimeRepository).markPipelineCompensating(99L);
  }

  private static Map<String, Object> attributesWithTemplate(boolean compensateOnFailure) {
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("compensate_on_failure", compensateOnFailure);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, template);
    return attributes;
  }

  /** 用真实 Iterable 实现避开 mock 严格模式对 iterator() 的“非必要 stub”告警（off 路径不遍历 provider）。 */
  private ObjectProvider<PipelineCompensator> provider(PipelineCompensator... compensators) {
    List<PipelineCompensator> list = new ArrayList<>(List.of(compensators));
    return new ListBackedCompensatorProvider(list);
  }

  private static final class RecordingCompensator implements PipelineCompensator {
    private final String type;
    private int calls;
    private CompensationResult result = CompensationResult.skipped("noop");

    RecordingCompensator(String type) {
      this.type = type;
    }

    @Override
    public String pipelineType() {
      return type;
    }

    @Override
    public CompensationResult compensate(
        String tenantId, Long pipelineInstanceId, Long fileId, Map<String, Object> attributes) {
      calls++;
      return result;
    }
  }

  /** 极简 ObjectProvider：只支持遍历（hook 唯一用到的能力）。 */
  private static final class ListBackedCompensatorProvider
      implements ObjectProvider<PipelineCompensator> {
    private final List<PipelineCompensator> list;

    ListBackedCompensatorProvider(List<PipelineCompensator> list) {
      this.list = list;
    }

    @Override
    public Iterator<PipelineCompensator> iterator() {
      return list.iterator();
    }

    @Override
    public PipelineCompensator getObject(Object... args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PipelineCompensator getObject() {
      throw new UnsupportedOperationException();
    }

    @Override
    public PipelineCompensator getIfAvailable() {
      return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public PipelineCompensator getIfUnique() {
      return list.size() == 1 ? list.get(0) : null;
    }
  }
}
