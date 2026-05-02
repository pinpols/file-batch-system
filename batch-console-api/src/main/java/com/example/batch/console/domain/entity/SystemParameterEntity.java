package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.system_parameter} 行的 MyBatis ResultMap 数据载体。CRUD 走 {@code
 * ConsoleSystemParameterMapper}，不可加 {@code @Table @Id}。
 */
@Data
public class SystemParameterEntity {

  private Long id;
  private String tenantId;
  private String paramKey;
  private String paramValue;
  private String description;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
