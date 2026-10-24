package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件束任务仅携带本分区模板的冻结配置")
class BundleFrozenFileTemplateBindingTest {

  @Test
  @DisplayName("分区模板身份优先于根参数且不把其它模板快照送给该任务")
  void shouldBindOnlyPartitionTemplate_whenFrozenBundleSnapshotExists() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("templateCode", "ROOT_OTHER");
    payload.put("frozenFileTemplateProtected", "caller-template");
    payload.put(
        "_frozenFileTemplatesProtected", Map.of("TPL_A", "protected-a", "TPL_B", "protected-b"));
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setTemplateCode("TPL_B");

    DefaultPartitionDispatchService.enrichBundleBinding(payload, partition);

    assertThat(payload).containsEntry("templateCode", "TPL_B");
    assertThat(payload).containsEntry("frozenFileTemplateProtected", "protected-b");
    assertThat(payload).doesNotContainKey("_frozenFileTemplatesProtected");
    assertThat(payload).doesNotContainValue("protected-a");
  }
}
