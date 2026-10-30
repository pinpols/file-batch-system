package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.console.domain.param.SecretVersionInsertParam;
import io.github.pinpols.batch.console.domain.query.SecretVersionQuery;
import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SecretVersionMapper {

  List<SecretVersionEntity> selectByQuery(SecretVersionQuery query);

  SecretVersionEntity selectById(
      @Param("tenantId") String tenantId, @Param("secretVersionId") Long secretVersionId);

  Integer selectLatestVersionNo(
      @Param("tenantId") String tenantId, @Param("secretRef") String secretRef);

  int acquireVersionLock(@Param("tenantId") String tenantId, @Param("secretRef") String secretRef);

  int insertSecretVersion(SecretVersionInsertParam params);

  int deactivateCurrentVersion(
      @Param("tenantId") String tenantId, @Param("secretRef") String secretRef);

  int updateSecretVersionStatus(
      @Param("tenantId") String tenantId,
      @Param("secretVersionId") Long secretVersionId,
      @Param("nextStatus") String nextStatus,
      @Param("updatedBy") String updatedBy);

  int revokeSecretVersion(
      @Param("tenantId") String tenantId,
      @Param("secretVersionId") Long secretVersionId,
      @Param("revokedBy") String revokedBy,
      @Param("revokeReason") String revokeReason);
}
