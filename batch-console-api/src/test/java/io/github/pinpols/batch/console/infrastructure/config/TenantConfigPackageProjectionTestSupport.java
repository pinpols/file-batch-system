package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import java.util.List;
import java.util.Map;

/** 让真实 Mapper 测试复用配置包工作簿的行投影入口。 */
public final class TenantConfigPackageProjectionTestSupport {
  private TenantConfigPackageProjectionTestSupport() {}

  public static Map<String, Object> jobRow(JobDefinitionView view) {
    return new TenantConfigPackageRowProjections(null, null).toJobRows(List.of(view), false).getFirst();
  }
}
