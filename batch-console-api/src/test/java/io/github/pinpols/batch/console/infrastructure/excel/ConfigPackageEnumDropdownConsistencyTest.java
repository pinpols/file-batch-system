package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.enums.ScheduleType;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 防漂移单测：锁定 {@link ConfigPackageExcelWorkbookWriter} 暴露给 Excel 模板下拉的 4 个 dropdown 数组 与后端真实 enum /
 * validator 集合 1:1 一致。
 *
 * <p>历史问题（v2 文档审计已发现）：
 *
 * <ul>
 *   <li>job_type 模板漏 {@code PROCESS}
 *   <li>schedule_type 模板多 {@code EVENT / ONE_TIME}（validator 拒收）
 *   <li>pipeline_type 模板漏 {@code PROCESS}
 *   <li>stage_code 模板含 {@code TRANSFER}（validator 拒收）
 * </ul>
 *
 * <p>本测试在 enum 改动或 validator 集合调整时立即把模板下拉漂移暴露在编译期，确保用户拿到的 Excel 模板 不会因为"按字段说明填了一个 validator 不认识的值"而
 * preview 失败。
 */
@DisplayName("配置包 Excel 下拉:与后端枚举及校验集合的防漂移一致性")
class ConfigPackageEnumDropdownConsistencyTest {

  @Test
  @DisplayName("作业类型下拉:与后端枚举取值及校验集合完全一致,漏列成员会被拦截")
  void shouldKeepJobTypeDropdownAligned_whenEnumChanges() {
    assertThat(ConfigPackageExcelWorkbookWriter.JOB_TYPE_DROPDOWN)
        .as("job_type 下拉必须包含 enum 全部 code，PROCESS 等漏列将被本断言拦截")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(JobType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.JOB_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.JOB_TYPES);
  }

  @Test
  @DisplayName("调度类型下拉:与后端枚举取值一致,已废弃取值不得再次出现")
  void shouldKeepScheduleTypeDropdownAligned_whenEnumChanges() {
    assertThat(ConfigPackageExcelWorkbookWriter.SCHEDULE_TYPE_DROPDOWN)
        .as("schedule_type 下拉必须 == enum；EVENT / ONE_TIME 等已废弃值不得再次出现")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(ScheduleType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.SCHEDULE_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.SCHEDULE_TYPES);
  }

  @Test
  @DisplayName("流水线类型下拉:与后端枚举取值及校验集合完全一致")
  void shouldKeepPipelineTypeDropdownAligned_whenEnumChanges() {
    assertThat(ConfigPackageExcelWorkbookWriter.PIPELINE_TYPE_DROPDOWN)
        .as("pipeline_type 下拉必须包含 enum 全部 code，PROCESS 漏列将被本断言拦截")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(PipelineType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.PIPELINE_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.PIPELINE_TYPES);
  }

  @Test
  @DisplayName("阶段编码下拉:与校验器阶段集合一致,历史取值已删除")
  void shouldKeepStageCodeDropdownAligned_whenValidatorChanges() {
    // stage_code 是跨 worker module 的 union，没单个 enum；以 validator STAGE_CODES 为权威。
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.STAGE_CODE_DROPDOWN))
        .as("stage_code 下拉必须 == validator STAGE_CODES；TRANSFER 等历史值已删除")
        .isEqualTo(ConfigPackageExcelValidator.STAGE_CODES);
  }

  @Test
  @DisplayName("下拉取值顺序:与枚举声明顺序一致,避免无序集合影响模板展示")
  void shouldKeepDropdownOrder_whenEnumDeclaresOrder() {
    // 业务认知顺序锁定，防止 Set#toArray unordered 行为污染 Excel 下拉展示。
    assertThat(ConfigPackageExcelWorkbookWriter.JOB_TYPE_DROPDOWN)
        .containsExactlyElementsOf(DictEnum.codeList(JobType.class));
    assertThat(ConfigPackageExcelWorkbookWriter.SCHEDULE_TYPE_DROPDOWN)
        .containsExactlyElementsOf(DictEnum.codeList(ScheduleType.class));
    assertThat(ConfigPackageExcelWorkbookWriter.PIPELINE_TYPE_DROPDOWN)
        .containsExactlyElementsOf(DictEnum.codeList(PipelineType.class));
  }

  private static Set<String> asSet(String[] arr) {
    return Set.copyOf(Arrays.asList(arr));
  }
}
