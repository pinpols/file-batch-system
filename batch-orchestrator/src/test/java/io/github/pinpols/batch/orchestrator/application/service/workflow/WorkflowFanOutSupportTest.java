package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowFanOutSupportTest {

  private WorkflowNodeEntity node(String nodeParams) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeParams(nodeParams);
    return n;
  }

  @Test
  void parseSpec_nullWhenNoFanOut() {
    assertThat(WorkflowFanOutSupport.parseSpec(node(null))).isNull();
    assertThat(WorkflowFanOutSupport.parseSpec(node("{}"))).isNull();
    assertThat(WorkflowFanOutSupport.parseSpec(node("{\"channelCode\":\"C1\"}"))).isNull();
  }

  @Test
  void parseSpec_readsExprWithDefaults() {
    WorkflowFanOutSupport.FanOutSpec spec =
        WorkflowFanOutSupport.parseSpec(
            node("{\"fanOut\":{\"itemsExpr\":\"$.nodes.SPLIT.output.shards\"}}"));
    assertThat(spec).isNotNull();
    assertThat(spec.itemsExpr()).isEqualTo("$.nodes.SPLIT.output.shards");
    assertThat(spec.itemParam()).isEqualTo("fanOutItem");
    assertThat(spec.maxFanOut()).isEqualTo(WorkflowFanOutSupport.DEFAULT_MAX_FAN_OUT);
  }

  @Test
  void parseSpec_honorsCustomItemParamAndMax() {
    WorkflowFanOutSupport.FanOutSpec spec =
        WorkflowFanOutSupport.parseSpec(
            node(
                "{\"fanOut\":{\"itemsExpr\":\"$.nodes.A.output.x\",\"itemParam\":\"shard\",\"maxFanOut\":5}}"));
    assertThat(spec.itemParam()).isEqualTo("shard");
    assertThat(spec.maxFanOut()).isEqualTo(5);
  }

  @Test
  void parseSpec_nullWhenItemsExprBlank() {
    assertThat(WorkflowFanOutSupport.parseSpec(node("{\"fanOut\":{\"itemsExpr\":\"\"}}"))).isNull();
  }

  @Test
  void expandPartitions_clonesTemplateNTimes() {
    SchedulePlan plan = new SchedulePlan();
    SchedulePlan.PartitionPlan tpl = new SchedulePlan.PartitionPlan();
    tpl.setPartitionKey("base");
    tpl.setBusinessKey("biz");
    tpl.setPartitionStatus("CREATED");
    plan.getPartitions().add(tpl);

    List<SchedulePlan.PartitionPlan> expanded = WorkflowFanOutSupport.expandPartitions(plan, 3);
    assertThat(expanded).hasSize(3);
    assertThat(expanded)
        .allSatisfy(
            p -> {
              assertThat(p.getPartitionKey()).isEqualTo("base");
              assertThat(p.getBusinessKey()).isEqualTo("biz");
              assertThat(p.getPartitionStatus()).isEqualTo("CREATED");
            });
  }

  @Test
  void expandPartitions_worksWithEmptyTemplate() {
    SchedulePlan plan = new SchedulePlan();
    List<SchedulePlan.PartitionPlan> expanded = WorkflowFanOutSupport.expandPartitions(plan, 2);
    assertThat(expanded).hasSize(2);
    assertThat(expanded.get(0).getPartitionKey()).isEqualTo("fanout");
  }

  @Test
  @SuppressWarnings("unchecked")
  void injectItem_addsItemAndIndexInfo() {
    String base = "{\"tenantId\":\"t1\",\"channelCode\":\"C1\"}";
    String out = WorkflowFanOutSupport.injectItem(base, "shard", Map.of("id", 7), 2, 4);
    Map<String, Object> parsed = (Map<String, Object>) JsonUtils.fromJson(out, Object.class);
    assertThat(parsed.get("tenantId")).isEqualTo("t1");
    assertThat(parsed.get("channelCode")).isEqualTo("C1");
    assertThat(parsed).containsKey("shard");
    assertThat(parsed.get("fanOutIndex")).isEqualTo(2);
    assertThat(parsed.get("fanOutTotal")).isEqualTo(4);
  }
}
