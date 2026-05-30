package com.example.batch.console.domain.rbac.mapper;

import com.example.batch.console.domain.rbac.entity.ResourceTagEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.resource_tag} MyBatis 映射（替代原 Spring Data JDBC {@code ConsoleResourceTagRepository}，
 * 对齐 CLAUDE.md §架构硬约束 — Console 配置表写入也走 MyBatis）。
 */
public interface ConsoleResourceTagMapper {

  List<ResourceTagEntity> findByResource(
      @Param("tenantId") String tenantId,
      @Param("resourceType") String resourceType,
      @Param("resourceCode") String resourceCode);

  List<ResourceTagEntity> findByTagKey(
      @Param("tenantId") String tenantId, @Param("tagKey") String tagKey);

  List<ResourceTagEntity> findByTagKeyAndValue(
      @Param("tenantId") String tenantId,
      @Param("tagKey") String tagKey,
      @Param("tagValue") String tagValue);

  List<String> findDistinctTagKeys(@Param("tenantId") String tenantId);

  Optional<ResourceTagEntity> findByResourceAndKey(
      @Param("tenantId") String tenantId,
      @Param("resourceType") String resourceType,
      @Param("resourceCode") String resourceCode,
      @Param("tagKey") String tagKey);

  /** Upsert：unique key 命中则更新 tag_value + created_by + created_at。 */
  void upsert(
      @Param("tenantId") String tenantId,
      @Param("resourceType") String resourceType,
      @Param("resourceCode") String resourceCode,
      @Param("tagKey") String tagKey,
      @Param("tagValue") String tagValue,
      @Param("operator") String operator);

  void deleteByResourceAndKey(
      @Param("tenantId") String tenantId,
      @Param("resourceType") String resourceType,
      @Param("resourceCode") String resourceCode,
      @Param("tagKey") String tagKey);

  void deleteAllByResource(
      @Param("tenantId") String tenantId,
      @Param("resourceType") String resourceType,
      @Param("resourceCode") String resourceCode);
}
