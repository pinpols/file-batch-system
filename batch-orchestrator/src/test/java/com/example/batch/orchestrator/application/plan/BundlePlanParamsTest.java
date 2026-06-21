package com.example.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.application.plan.BundlePlanParams.BundleFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BundlePlanParamsTest {

  @Test
  void extractsValidBundleFiles() {
    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 101, "templateCode", "TPL_A"),
                Map.of("sourceFileId", 102L, "templateCode", "TPL_B", "targetRef", "biz.t")));
    List<BundleFile> files = BundlePlanParams.extract(params);
    assertThat(files).hasSize(2);
    assertThat(files.get(0).sourceFileId()).isEqualTo(101L);
    assertThat(files.get(0).templateCode()).isEqualTo("TPL_A");
    assertThat(files.get(0).targetRef()).isNull();
    assertThat(files.get(1).sourceFileId()).isEqualTo(102L);
    assertThat(files.get(1).targetRef()).isEqualTo("biz.t");
  }

  @Test
  void skipsEntriesMissingFileIdOrTemplate() {
    // 缺 sourceFileId 或 templateCode 的项跳过(承重信息,绝不落一个无绑定 partition)
    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("templateCode", "TPL_NO_FILE"),
                Map.of("sourceFileId", 200),
                Map.of("sourceFileId", 201, "templateCode", "TPL_OK")));
    List<BundleFile> files = BundlePlanParams.extract(params);
    assertThat(files).hasSize(1);
    assertThat(files.get(0).sourceFileId()).isEqualTo(201L);
  }

  @Test
  void returnsEmptyForNullParamsOrMissingKeyOrNonList() {
    assertThat(BundlePlanParams.extract(null)).isEmpty();
    assertThat(BundlePlanParams.extract(Map.of())).isEmpty();
    assertThat(BundlePlanParams.extract(Map.of("bundleFiles", "not-a-list"))).isEmpty();
  }
}
