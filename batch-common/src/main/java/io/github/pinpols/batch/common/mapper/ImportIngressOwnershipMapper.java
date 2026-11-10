package io.github.pinpols.batch.common.mapper;

import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Param;

/** 读取 Import 与对象对账共同使用的入口归属声明。 */
public interface ImportIngressOwnershipMapper {

  List<OwnershipRow> findActiveScope(@Param("scopeId") String scopeId);

  List<OwnershipRow> listProtectedScopes(@Param("storageId") String storageId);

  /** 一条归属及其可选绑定；无绑定的范围仍保留一行。 */
  @Data
  class OwnershipRow {
    private String scopeId;
    private String storageId;
    private String bucketName;
    private String scopePrefix;
    private String state;
    private String bindingType;
    private String tenantId;
    private String bindingPrefix;
    private String fileGroupCode;
    private String manifestKeyTemplate;
    private String channelCode;
    private boolean enabled;
  }
}
