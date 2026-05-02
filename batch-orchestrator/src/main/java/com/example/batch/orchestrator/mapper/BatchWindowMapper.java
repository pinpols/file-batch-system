package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchWindowEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.batch_window CRUD。原 {@code BatchWindowRepository}（Spring Data JDBC）已下线， 配置态写读统一由本 Mapper
 * 接管。
 */
public interface BatchWindowMapper {

  BatchWindowEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled);

  List<BatchWindowEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  BatchWindowEntity selectById(@Param("id") Long id);

  int insert(BatchWindowEntity record);

  int update(BatchWindowEntity record);

  int deleteById(@Param("id") Long id);
}
