package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Console 侧只读查询 {@code batch.step_registry}：Excel 上传时按 pipeline 的 type 查各模块
 * 已注册的 impl_code 白名单，拦住指向未注册 Spring bean 的坏 seed。
 */
public interface StepRegistryQueryMapper {

  List<String> selectImplCodesByModule(@Param("module") String module);

  /** 所有模块的 impl_code 并集；模板下载时用作跨模块的总下拉选项。 */
  List<String> selectAllImplCodes();

  /**
   * 全部 (module, impl_code) 行。模板下载时下拉项格式化为 {@code MODULE:beanName}，方便用户辨识模块归属；
   * 上传校验时对照 pipeline_type 验证前缀匹配。
   */
  List<Map<String, String>> selectAllImplEntries();
}
