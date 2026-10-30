package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

/** 系统租户目录仅供有界后台协调进入各租户 RLS 上下文。 */
public interface TenantDirectoryMapper {

  List<String> selectRunnableTenantIds();

  /** 用于运维汇总指标和保留检查的全部租户，包括已暂停租户。 */
  List<String> selectAllTenantIds();
}
