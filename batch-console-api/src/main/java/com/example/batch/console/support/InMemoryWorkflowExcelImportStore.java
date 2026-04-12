package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryWorkflowExcelImportStore implements WorkflowExcelImportStore {

  private final Map<String, WorkflowExcelSession> sessions = new ConcurrentHashMap<>();

  @Override
  public String save(
      String fileName,
      String tenantId,
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges) {
    String token = UUID.randomUUID().toString();
    sessions.put(
        token,
        new WorkflowExcelSession(fileName, tenantId, Instant.now(), definitions, nodes, edges));
    return token;
  }

  @Override
  public WorkflowExcelSession get(String uploadToken) {
    return sessions.get(uploadToken);
  }

  @Override
  public void remove(String uploadToken) {
    sessions.remove(uploadToken);
  }
}
