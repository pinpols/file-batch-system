package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.relational.core.mapping.Column;

/**
 * {@code batch.resource_tag} 行的 MyBatis ResultMap 数据载体。
 *
 * <p><b>不是 Spring Data JDBC 实体</b>—— CRUD 全走 {@code ConsoleResourceTagMapper}。 {@code @Column}
 * 注解保留作可读性（MyBatis 无视）；不能加 {@code @Table @Id}。
 */
@Data
public class ResourceTagEntity {

  private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("resource_type")
  private String resourceType;

  @Column("resource_code")
  private String resourceCode;

  @Column("tag_key")
  private String tagKey;

  @Column("tag_value")
  private String tagValue;

  @Column("created_by")
  private String createdBy;

  @Column("created_at")
  private Instant createdAt;
}
