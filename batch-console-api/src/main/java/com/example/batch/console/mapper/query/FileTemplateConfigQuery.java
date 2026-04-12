package com.example.batch.console.mapper.query;

import com.example.batch.common.model.PageRequest;

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
    return new FileTemplateConfigQuery(tenantId, null, null, null, null, null, null, pageRequest);
  }
}
