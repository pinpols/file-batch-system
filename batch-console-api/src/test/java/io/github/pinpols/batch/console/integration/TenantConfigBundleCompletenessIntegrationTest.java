package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest.EdgeItem;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest.NodeItem;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 配置包采用完整集合语义，不能受交互列表的单页上限截断。 */
@DisplayName("租户配置包完整集合校验")
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class TenantConfigBundleCompletenessIntegrationTest extends AbstractIntegrationTest {

  private final ConsoleTenantConfigCopyService copyService;

  private final JdbcTemplate jdbc;

  private final WorkflowDagValidator graphValidator;

  private String tenant;

  @Autowired
  TenantConfigBundleCompletenessIntegrationTest(
      ConsoleTenantConfigCopyService copyService,
      JdbcTemplate jdbc,
      WorkflowDagValidator graphValidator) {
    this.copyService = copyService;
    this.jdbc = jdbc;
    this.graphValidator = graphValidator;
  }

  @BeforeEach
  void createTenant() {
    tenant = "bundle-complete-" + UUID.randomUUID().toString().replace("-", "");
    String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
    jdbc.update(insertTenantSql, tenant, tenant);
  }

  @AfterEach
  void removeOwnedFixtures() {
    String deleteEdgesSql = """
        delete from batch.workflow_edge where tenant_id = ?
        """;
    String deleteNodesSql = """
        delete from batch.workflow_node where tenant_id = ?
        """;
    String deleteWorkflowsSql = """
        delete from batch.workflow_definition where tenant_id = ?
        """;
    String deleteQueuesSql = """
        delete from batch.resource_queue where tenant_id = ?
        """;
    String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
        """;
    jdbc.update(deleteEdgesSql, tenant);
    jdbc.update(deleteNodesSql, tenant);
    jdbc.update(deleteWorkflowsSql, tenant);
    jdbc.update(deleteQueuesSql, tenant);
    jdbc.update(deleteTenantSql, tenant);
  }

  @Test
  @DisplayName("第201条资源队列进入完整配置包和差异预览，其他租户不混入")
  void shouldIncludeEveryQueue_whenTenantHasMoreThanOneInteractivePage() {
    String insertQueuesSql = """
        insert into batch.resource_queue (tenant_id, queue_code, queue_name, queue_type)
        select ?, 'queue-' || item, 'Queue ' || item, 'MIXED'
        from generate_series(1, 201) item
        """;
    jdbc.update(insertQueuesSql, tenant);
    ConfigSyncBundlePayload bundle =
        copyService.buildBundle(tenant, Set.of(ConfigType.RESOURCE_QUEUE));

    assertThat(bundle.getResourceQueues()).hasSize(201);
    assertThat(bundle.getResourceQueues()).extracting("queueCode").contains("queue-1", "queue-201");
    TenantConfigPreviewRequest request = new TenantConfigPreviewRequest();
    request.setSourceTenantId(tenant);
    request.setTargetTenantIds(List.of("missing-" + tenant));
    request.setConfigTypes(Set.of(ConfigType.RESOURCE_QUEUE));
    TenantConfigDiffPreviewResponse preview = copyService.preview(request);
    assertThat(preview.summary().addCount()).isEqualTo(201);
    assertThat(preview.tenants().getFirst().overlayBundle().getResourceQueues()).hasSize(201);
  }

  @Test
  @DisplayName("配置包按编码只导出当前最高版本，旧定义不重复进入目标应用集合")
  void shouldExportOnlyLatestWorkflowDefinitionForEachCode() {
    insertSmallWorkflowVersion("versioned-workflow", "Older workflow", 1);
    insertSmallWorkflowVersion("versioned-workflow", "Current workflow", 3);
    String disableCurrentSql = """
        update batch.workflow_definition set enabled = false
        where tenant_id = ? and workflow_code = 'versioned-workflow' and version = 3
        """;
    jdbc.update(disableCurrentSql, tenant);

    ConfigSyncBundlePayload bundle =
        copyService.buildBundle(tenant, Set.of(ConfigType.WORKFLOW_DEFINITION));

    assertThat(bundle.getWorkflowDefinitions()).singleElement().satisfies(workflow -> {
      assertThat(workflow.getWorkflowCode()).isEqualTo("versioned-workflow");
      assertThat(workflow.getWorkflowName()).isEqualTo("Current workflow");
      assertThat(workflow.getEnabled()).isFalse();
      assertThat(workflow.getNodes())
          .extracting("nodeName")
          .containsExactlyInAnyOrder("Current workflow start", "Current workflow end");
    });
  }

  private void insertSmallWorkflowVersion(String code, String name, int version) {
    String insertWorkflowSql = """
        insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, workflow_type, version)
        values (?, ?, ?, 'DAG', ?) returning id
        """;
    Long definitionId =
        jdbc.queryForObject(insertWorkflowSql, Long.class, tenant, code, name, version);
    String insertNodesSql = """
        insert into batch.workflow_node
            (tenant_id, workflow_definition_id, node_code, node_name, node_type)
        select ?, ?, item.code, ? || ' ' || item.code, item.kind
        from (values ('start', 'START'), ('end', 'END')) item(code, kind)
        """;
    String insertEdgeSql = """
        insert into batch.workflow_edge
            (tenant_id, workflow_definition_id, from_node_code, to_node_code)
        values (?, ?, 'start', 'end')
        """;
    jdbc.update(insertNodesSql, tenant, definitionId, name);
    jdbc.update(insertEdgeSql, tenant, definitionId);
  }

  @Test
  @DisplayName("合法Workflow图的节点不超过200时，仍须保留第201条以后的边")
  void shouldIncludeEntireWorkflowGraph_whenGraphExceedsOneInteractivePage() {
    String insertWorkflowSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name)
        values (?, 'large-graph', 'Large graph') returning id
        """;
    Long definitionId = jdbc.queryForObject(insertWorkflowSql, Long.class, tenant);
    String insertNodesSql = """
        insert into batch.workflow_node
            (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, node_params)
        select ?, ?, 'node-' || item, 'Node ' || item,
            case when item = 1 then 'START' when item = 150 then 'END' else 'GATEWAY' end,
            item, '{"joinMode":"ALL"}'::jsonb
        from generate_series(1, 150) item
        """;
    String insertEdgesSql = """
        insert into batch.workflow_edge
            (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type)
        select ?, ?, 'node-' || item, 'node-' || (item + 1), 'SUCCESS'
        from generate_series(1, 149) item
        union all
        select ?, ?, 'node-1', 'node-' || item, 'SUCCESS'
        from generate_series(3, 149) item
        """;
    jdbc.update(insertNodesSql, tenant, definitionId);
    jdbc.update(insertEdgesSql, tenant, definitionId, tenant, definitionId);
    String selectNodesSql = """
        select node_code, node_type, node_params::text from batch.workflow_node
        where tenant_id = ? and workflow_definition_id = ?
        """;
    String selectEdgesSql = """
        select from_node_code, to_node_code from batch.workflow_edge
        where tenant_id = ? and workflow_definition_id = ?
        """;
    List<NodeItem> nodes = jdbc.query(
        selectNodesSql,
        (row, index) -> {
          NodeItem node = new NodeItem();
          node.setNodeCode(row.getString("node_code"));
          node.setNodeType(row.getString("node_type"));
          node.setNodeParams(row.getString("node_params"));
          return node;
        },
        tenant,
        definitionId);
    List<EdgeItem> edges = jdbc.query(
        selectEdgesSql,
        (row, index) -> {
          EdgeItem edge = new EdgeItem();
          edge.setFromNodeCode(row.getString("from_node_code"));
          edge.setToNodeCode(row.getString("to_node_code"));
          return edge;
        },
        tenant,
        definitionId);
    WorkflowDefinitionSaveRequest graph = new WorkflowDefinitionSaveRequest();
    graph.setNodes(nodes);
    graph.setEdges(edges);
    graphValidator.validate(tenant, graph);

    ConfigSyncBundlePayload bundle =
        copyService.buildBundle(tenant, Set.of(ConfigType.WORKFLOW_DEFINITION));

    assertThat(bundle.getWorkflowDefinitions()).singleElement().satisfies(workflow -> {
      assertThat(workflow.getNodes()).hasSize(150);
      assertThat(workflow.getEdges()).hasSize(296);
      assertThat(workflow.getNodes()).extracting("nodeCode").contains("node-150");
      assertThat(workflow.getEdges()).extracting("toNodeCode").contains("node-150");
    });
  }
}
