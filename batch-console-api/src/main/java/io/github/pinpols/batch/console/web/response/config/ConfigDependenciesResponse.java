package io.github.pinpols.batch.console.web.response.config;

import java.util.List;

/** 配置项被作业引用的依赖关系。 */
public record ConfigDependenciesResponse(
    String configType,
    String configCode,
    List<DependentJobResponse> dependentJobs,
    int dependentJobCount) {

  /** 引用配置项的作业摘要。 */
  public record DependentJobResponse(Long id, String code, String name) {}
}
