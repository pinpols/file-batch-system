package io.github.pinpols.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.orchestrator.application.plan.BundlePlanParams.BundleFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("束计划参数解析: 按作业类型提取必需绑定字段的口径")
class BundlePlanParamsTest {

  @Test
  @DisplayName("束文件清单合法时按顺序提取文件标识, 模板与目标引用")
  void shouldExtractValidFiles_whenBundleFilesGiven() {
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 101, "templateCode", "TPL_A"),
            Map.of("sourceFileId", 102L, "templateCode", "TPL_B", "targetRef", "biz.t")));
    List<BundleFile> files = BundlePlanParams.extract(params, JobType.BUNDLE_IMPORT);
    assertThat(files).hasSize(2);
    assertThat(files.get(0).sourceFileId()).isEqualTo(101L);
    assertThat(files.get(0).templateCode()).isEqualTo("TPL_A");
    assertThat(files.get(0).targetRef()).isNull();
    assertThat(files.get(1).sourceFileId()).isEqualTo(102L);
    assertThat(files.get(1).targetRef()).isEqualTo("biz.t");
  }

  @Test
  @DisplayName("导入类型缺少文件标识或模板的条目被跳过, 只保留完整条目")
  void shouldSkipEntries_whenRequiredBindingMissing() {
    // BUNDLE_IMPORT:缺 sourceFileId 或 templateCode 的项跳过(承重信息,绝不落一个无绑定 partition)
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("templateCode", "TPL_NO_FILE"),
            Map.of("sourceFileId", 200),
            Map.of("sourceFileId", 201, "templateCode", "TPL_OK")));
    List<BundleFile> files = BundlePlanParams.extract(params, JobType.BUNDLE_IMPORT);
    assertThat(files).hasSize(1);
    assertThat(files.get(0).sourceFileId()).isEqualTo(201L);
  }

  @Test
  @DisplayName("导出类型只要求模板, 允许条目没有源文件")
  void exportBundleRequiresTemplateOnly_noSourceFile() {
    // BUNDLE_EXPORT:导出无源文件,承重 = templateCode(=源表/查询);仅缺模板的项跳过。
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("templateCode", "EXP_RISK"),
            Map.of("templateCode", "EXP_TRADE", "targetRef", "sftp-a"),
            Map.of("targetRef", "no-template-skip")));
    List<BundleFile> files = BundlePlanParams.extract(params, JobType.BUNDLE_EXPORT);
    assertThat(files).hasSize(2);
    assertThat(files.get(0).sourceFileId()).isNull();
    assertThat(files.get(0).templateCode()).isEqualTo("EXP_RISK");
    assertThat(files.get(1).targetRef()).isEqualTo("sftp-a");
  }

  @Test
  @DisplayName("分发类型要求文件标识与目标引用, 不要求模板")
  void dispatchBundleRequiresFileAndTarget_noTemplate() {
    // BUNDLE_DISPATCH:分发无模板,承重 = sourceFileId(待分发文件) + targetRef(下游渠道)。
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 301, "targetRef", "CH_SFTP"),
            Map.of("sourceFileId", 302, "targetRef", "CH_OSS", "templateCode", "ignored"),
            Map.of("sourceFileId", 303),
            Map.of("targetRef", "CH_NO_FILE")));
    List<BundleFile> files = BundlePlanParams.extract(params, JobType.BUNDLE_DISPATCH);
    assertThat(files).hasSize(2);
    assertThat(files.get(0).sourceFileId()).isEqualTo(301L);
    assertThat(files.get(0).targetRef()).isEqualTo("CH_SFTP");
    assertThat(files.get(1).sourceFileId()).isEqualTo(302L);
  }

  @Test
  @DisplayName("非束类型, 参数为空或清单不是列表时返回空")
  void shouldReturnEmpty_whenJobTypeOrParamsInvalid() {
    Map<String, Object> valid =
        Map.of("bundleFiles", List.of(Map.of("sourceFileId", 1, "templateCode", "T")));
    // 非束类型 → 空(走原同构路径)
    assertThat(BundlePlanParams.extract(valid, JobType.IMPORT)).isEmpty();
    assertThat(BundlePlanParams.extract(valid, null)).isEmpty();
    assertThat(BundlePlanParams.extract(null, JobType.BUNDLE_IMPORT)).isEmpty();
    assertThat(BundlePlanParams.extract(Map.of(), JobType.BUNDLE_IMPORT)).isEmpty();
    assertThat(BundlePlanParams.extract(Map.of("bundleFiles", "not-a-list"), JobType.BUNDLE_IMPORT))
        .isEmpty();
  }
}
