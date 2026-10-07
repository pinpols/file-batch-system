package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流程扇出支持: 扇出规格解析, 分区展开与目标引用绑定口径")
class WorkflowFanOutSupportTest {

  private WorkflowNodeEntity node(String nodeParams) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeParams(nodeParams);
    return n;
  }

  @Test
  @DisplayName("未配置扇出或配置为空时解析结果为空")
  void parseSpec_nullWhenNoFanOut() {
    assertThat(WorkflowFanOutSupport.parseSpec(node(null))).isNull();
    assertThat(WorkflowFanOutSupport.parseSpec(node("{}"))).isNull();
    assertThat(WorkflowFanOutSupport.parseSpec(node("{\"channelCode\":\"C1\"}")))
        .isNull();
  }

  @Test
  @DisplayName("解析扇出规格时读取条目表达式并套用默认条目参数与数量上限")
  void parseSpec_readsExprWithDefaults() {
    WorkflowFanOutSupport.FanOutSpec spec = WorkflowFanOutSupport.parseSpec(
        node("{\"fanOut\":{\"itemsExpr\":\"$.nodes.SPLIT.output.shards\"}}"));
    assertThat(spec).isNotNull();
    assertThat(spec.itemsExpr()).isEqualTo("$.nodes.SPLIT.output.shards");
    assertThat(spec.itemParam()).isEqualTo("fanOutItem");
    assertThat(spec.maxFanOut()).isEqualTo(WorkflowFanOutSupport.DEFAULT_MAX_FAN_OUT);
  }

  @Test
  @DisplayName("扇出规格给出自定义条目参数与上限时按配置生效")
  void parseSpec_honorsCustomItemParamAndMax() {
    WorkflowFanOutSupport.FanOutSpec spec = WorkflowFanOutSupport.parseSpec(
        node(
            "{\"fanOut\":{\"itemsExpr\":\"$.nodes.A.output.x\",\"itemParam\":\"shard\",\"maxFanOut\":5}}"));
    assertThat(spec.itemParam()).isEqualTo("shard");
    assertThat(spec.maxFanOut()).isEqualTo(5);
  }

  @Test
  @DisplayName("扇出条目表达式为空时解析结果为空")
  void parseSpec_nullWhenItemsExprBlank() {
    assertThat(WorkflowFanOutSupport.parseSpec(node("{\"fanOut\":{\"itemsExpr\":\"\"}}")))
        .isNull();
  }

  @Test
  @DisplayName("按扇出数量复制分区模板, 分配分片序号与总数并保留分区键")
  void expandPartitions_clonesTemplateNTimes() {
    SchedulePlan plan = new SchedulePlan();
    SchedulePlan.PartitionPlan tpl = new SchedulePlan.PartitionPlan();
    tpl.setPartitionKey("base");
    tpl.setBusinessKey("biz");
    tpl.setPartitionStatus("CREATED");
    plan.getPartitions().add(tpl);

    List<SchedulePlan.PartitionPlan> expanded = WorkflowFanOutSupport.expandPartitions(plan, 3);
    assertThat(expanded).hasSize(3);
    assertThat(expanded.get(0).getShardIndex()).isZero();
    assertThat(expanded.get(0).getShardTotal()).isEqualTo(3);
    assertThat(expanded.get(2).getShardIndex()).isEqualTo(2);
    assertThat(expanded.get(2).getShardTotal()).isEqualTo(3);
    assertThat(expanded).allSatisfy(p -> {
      assertThat(p.getPartitionKey()).isEqualTo("base");
      assertThat(p.getBusinessKey()).isEqualTo("biz");
      assertThat(p.getPartitionStatus()).isEqualTo("CREATED");
    });
  }

  @Test
  @DisplayName("模板为空时仍按扇出数量展开分区并套用默认分区键")
  void expandPartitions_worksWithEmptyTemplate() {
    SchedulePlan plan = new SchedulePlan();
    List<SchedulePlan.PartitionPlan> expanded = WorkflowFanOutSupport.expandPartitions(plan, 2);
    assertThat(expanded).hasSize(2);
    assertThat(expanded.get(0).getPartitionKey()).isEqualTo("fanout");
  }

  @Test
  @DisplayName("派发类型分区的目标引用按条目顺序绑定对应渠道")
  void bindDispatchTargetRefs_projectsEachItemChannelBeforeAdmission() {
    SchedulePlan plan = new SchedulePlan();
    plan.setDefaultWorkerType("DISPATCH");
    List<SchedulePlan.PartitionPlan> partitions = WorkflowFanOutSupport.expandPartitions(plan, 3);
    List<Object> items = List.of(
        Map.of("channelCode", "SFTP_A"),
        Map.of("dispatchChannelCode", "OSS_B"),
        Map.of("targetChannelCode", "HTTP_C"));

    WorkflowFanOutSupport.bindDispatchTargetRefs(plan, items, partitions);

    assertThat(partitions)
        .extracting(SchedulePlan.PartitionPlan::getTargetRef)
        .containsExactly("SFTP_A", "OSS_B", "HTTP_C");
  }

  @Test
  @DisplayName("非派发类型分区不把条目渠道写入目标引用")
  void bindDispatchTargetRefs_doesNotTreatNonDispatchItemsAsChannels() {
    SchedulePlan plan = new SchedulePlan();
    plan.setDefaultWorkerType("PROCESS");
    List<SchedulePlan.PartitionPlan> partitions = WorkflowFanOutSupport.expandPartitions(plan, 1);

    WorkflowFanOutSupport.bindDispatchTargetRefs(
        plan, List.of(Map.of("channelCode", "SFTP_A")), partitions);

    assertThat(partitions.getFirst().getTargetRef()).isNull();
  }

  @Test
  @DisplayName("注入扇出条目时追加条目内容与序号总数, 并保留原有字段")
  @SuppressWarnings("unchecked")
  void injectItem_addsItemAndIndexInfo() {
    String base = "{\"tenantId\":\"t1\",\"channelCode\":\"C1\"}";
    String out = WorkflowFanOutSupport.injectItem(base, "shard", Map.of("id", 7), 2, 4);
    Map<String, Object> parsed = (Map<String, Object>) JsonUtils.fromJson(out, Object.class);
    assertThat(parsed).containsEntry("tenantId", "t1");
    assertThat(parsed).containsEntry("channelCode", "C1");
    assertThat(parsed).containsKey("shard");
    assertThat(parsed).containsEntry("fanOutIndex", 2);
    assertThat(parsed).containsEntry("fanOutTotal", 4);
  }
}
