package com.example.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.ScheduleType;
import java.util.Arrays;
import java.util.Set;
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
class ConfigPackageEnumDropdownConsistencyTest {

  @Test
  void jobTypeDropdownEqualsJobTypeEnum() {
    assertThat(ConfigPackageExcelWorkbookWriter.JOB_TYPE_DROPDOWN)
        .as("job_type 下拉必须包含 enum 全部 code，PROCESS 等漏列将被本断言拦截")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(JobType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.JOB_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.JOB_TYPES);
  }

  @Test
  void scheduleTypeDropdownEqualsScheduleTypeEnum() {
    assertThat(ConfigPackageExcelWorkbookWriter.SCHEDULE_TYPE_DROPDOWN)
        .as("schedule_type 下拉必须 == enum；EVENT / ONE_TIME 等已废弃值不得再次出现")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(ScheduleType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.SCHEDULE_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.SCHEDULE_TYPES);
  }

  @Test
  void pipelineTypeDropdownEqualsPipelineTypeEnum() {
    assertThat(ConfigPackageExcelWorkbookWriter.PIPELINE_TYPE_DROPDOWN)
        .as("pipeline_type 下拉必须包含 enum 全部 code，PROCESS 漏列将被本断言拦截")
        .containsExactlyInAnyOrderElementsOf(DictEnum.codes(PipelineType.class));
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.PIPELINE_TYPE_DROPDOWN))
        .isEqualTo(ConfigPackageExcelValidator.PIPELINE_TYPES);
  }

  @Test
  void stageCodeDropdownEqualsValidatorStageCodes() {
    // stage_code 是跨 worker module 的 union，没单个 enum；以 validator STAGE_CODES 为权威。
    assertThat(asSet(ConfigPackageExcelWorkbookWriter.STAGE_CODE_DROPDOWN))
        .as("stage_code 下拉必须 == validator STAGE_CODES；TRANSFER 等历史值已删除")
        .isEqualTo(ConfigPackageExcelValidator.STAGE_CODES);
  }

  @Test
  void dropdownOrderFollowsEnumDeclarationOrder() {
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
