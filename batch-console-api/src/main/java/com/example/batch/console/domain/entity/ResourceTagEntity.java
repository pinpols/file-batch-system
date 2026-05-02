package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.resource_tag} 行的 MyBatis ResultMap 数据载体。
 *
 * <p>CRUD 全走 {@code ConsoleResourceTagMapper}；不能加 {@code @Table}/{@code @Id} 等 ORM 映射注解。
 */
@Data
public class ResourceTagEntity {

  private Long id;

  private String tenantId;

  private String resourceType;

  private String resourceCode;

  private String tagKey;

  private String tagValue;

  private String createdBy;

  private Instant createdAt;
}
