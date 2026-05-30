package com.example.batch.console.domain.file.query;

import com.example.batch.common.model.PageRequest;
import lombok.Builder;

@Builder
public record FileTemplateConfigQuery(
    String tenantId,
    String keyword,
    String templateCode,
    String templateName,
    String templateType,
    String bizType,
    Boolean enabled,
    PageRequest pageRequest) {

  public static FileTemplateConfigQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return builder().tenantId(tenantId).pageRequest(pageRequest).build();
  }
}
