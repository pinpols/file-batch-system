package io.github.pinpols.batch.console.domain.workflow.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 节点与连线文本长度契约")
class WorkflowTextLengthValidationTest {
  private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
  private final Validator validator = validatorFactory.getValidator();

  @AfterEach
  void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  @DisplayName("创建和完整更新按 Unicode 码点校验节点名称与边条件")
  void shouldValidateNestedTextsForCreateAndFullUpdate() {
    WorkflowDefinitionSaveRequest request = definition("😀".repeat(256), "😀".repeat(1024));
    assertThat(validator.validate(request)).isEmpty();

    request.getNodes().getFirst().setNodeName("😀".repeat(257));
    request.getEdges().getFirst().setConditionExpr("😀".repeat(1025));
    assertThat(validator.validate(request))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("nodes[0].nodeName", "edges[0].conditionExpr");

    WorkflowDefinitionFullUpdateRequest full = new WorkflowDefinitionFullUpdateRequest();
    full.setDefinition(request);
    full.setExpectedVersion(1);
    assertThat(validator.validate(full))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("definition.nodes[0].nodeName", "definition.edges[0].conditionExpr");
  }

  private static WorkflowDefinitionSaveRequest definition(String name, String condition) {
    WorkflowDefinitionSaveRequest.NodeItem node = new WorkflowDefinitionSaveRequest.NodeItem();
    node.setNodeCode("start");
    node.setNodeName(name);
    node.setNodeType("START");
    WorkflowDefinitionSaveRequest.EdgeItem edge = new WorkflowDefinitionSaveRequest.EdgeItem();
    edge.setFromNodeCode("start");
    edge.setToNodeCode("end");
    edge.setConditionExpr(condition);
    WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest();
    request.setTenantId("tenant-a");
    request.setWorkflowCode("WF_A");
    request.setNodes(List.of(node));
    request.setEdges(List.of(edge));
    return request;
  }
}
