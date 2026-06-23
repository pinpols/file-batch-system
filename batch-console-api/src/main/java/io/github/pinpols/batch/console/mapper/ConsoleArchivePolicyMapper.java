package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.entity.ArchivePolicyEntity;
import io.github.pinpols.batch.console.domain.param.ArchivePolicyUpsertParam;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/** {@code batch.archive_policy} MyBatis 映射（替代原 Spring Data JDBC ConsoleArchivePolicyRepository）。 */
public interface ConsoleArchivePolicyMapper {

  List<ArchivePolicyEntity> findAllByTenant(@Param("tenantId") String tenantId);

  Optional<ArchivePolicyEntity> findByTenantAndTable(
      @Param("tenantId") String tenantId, @Param("targetTable") String targetTable);

  /** ON CONFLICT (tenant_id, target_table) DO UPDATE 语义。 */
  void upsert(@Param("p") ArchivePolicyUpsertParam p);
}
