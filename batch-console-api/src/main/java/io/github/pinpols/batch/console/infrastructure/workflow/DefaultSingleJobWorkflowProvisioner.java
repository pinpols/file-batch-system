package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 新导出和模板不再发布租户自有 Pipeline 配置。 */
@Component
@RequiredArgsConstructor
public class DefaultSingleJobWorkflowProvisioner {

  private static final String START = "START";
  private static final String JOB = "JOB";
  private static final String END = "END";
  private static final String DEFAULT_CALENDAR_CODE = "default_calendar";

  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;

  public void provision(JobDefinitionEntity job) {
    WorkflowDefinitionEntity existing =
        workflowDefinitionMapper.selectBySingleJobDefinitionId(job.getTenantId(), job.getId());
    if (EmptyChecks.isNotNull(existing)) {
      return;
    }
    WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
    workflow.setTenantId(job.getTenantId());
    workflow.setWorkflowCode("JOB-WF-" + job.getId());
    workflow.setWorkflowName(job.getJobCode() + " 单作业工作流");
    workflow.setWorkflowType("DAG");
    workflow.setVersion(1);
    workflow.setEnabled(Boolean.TRUE);
    workflow.setDescription("System-managed single-job workflow for " + job.getJobCode());
    workflow.setSingleJobDefinitionId(job.getId());
    workflow.setScheduleEnabled(Boolean.FALSE);
    workflow.setScheduleType(job.getScheduleType());
    workflow.setScheduleExpr(job.getScheduleExpr());
    workflow.setTimezone(job.getTimezone());
    workflow.setTriggerMode(job.getTriggerMode());
    String calendarCode = CodeNormalizer.toConfigFormOrNull(job.getCalendarCode());
    if (!Texts.hasText(calendarCode)) {
      calendarCode = DEFAULT_CALENDAR_CODE;
    }
    ActiveCalendarRow activeBusinessCalendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(job.getTenantId(), calendarCode);
    if (EmptyChecks.isNull(activeBusinessCalendar)) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.calendar_not_found", calendarCode);
    }
    workflow.setCalendarCode(calendarCode);
    workflow.setDependsOnJobCode(job.getDependsOnJobCode());
    workflowDefinitionMapper.insert(workflow);

    upsertNode(job, workflow.getId(), START, 0);
    upsertNode(job, workflow.getId(), JOB, 1);
    upsertNode(job, workflow.getId(), END, 2);
    upsertEdge(job, workflow.getId(), START, JOB);
    upsertEdge(job, workflow.getId(), JOB, END);
    return;
  }

  private void upsertNode(
      JobDefinitionEntity job, Long workflowId, String nodeType, int nodeOrder) {
    // 托管单作业图的节点代码与类型固定一致，避免调用方拼出不一致的控制节点。
    String nodeName =
        switch (nodeType) {
          case START -> "开始";
          case JOB -> job.getJobName();
          case END -> "结束";
          default ->
            throw new IllegalArgumentException("Unsupported managed workflow node: " + nodeType);
        };
    String relatedJobCode = JOB.equals(nodeType) ? job.getJobCode() : null;
    WorkflowNodeUpsertParam node = new WorkflowNodeUpsertParam();
    node.setTenantId(job.getTenantId());
    node.setWorkflowDefinitionId(workflowId);
    node.setNodeCode(nodeType);
    node.setNodeName(nodeName);
    node.setNodeType(nodeType);
    node.setRelatedJobCode(relatedJobCode);
    if (JOB.equals(nodeType)) {
      node.setWindowCode(job.getWindowCode());
    }
    node.setNodeOrder(nodeOrder);
    node.setRetryPolicy("NONE");
    node.setRetryMaxCount(0);
    node.setTimeoutSeconds(0);
    node.setEnabled(Boolean.TRUE);
    workflowNodeMapper.upsertWorkflowNode(node);
  }

  private void upsertEdge(JobDefinitionEntity job, Long workflowId, String from, String to) {
    WorkflowEdgeUpsertParam edge = new WorkflowEdgeUpsertParam();
    edge.setTenantId(job.getTenantId());
    edge.setWorkflowDefinitionId(workflowId);
    edge.setFromNodeCode(from);
    edge.setToNodeCode(to);
    edge.setEdgeType("SUCCESS");
    edge.setEnabled(Boolean.TRUE);
    workflowEdgeMapper.upsertWorkflowEdge(edge);
  }
}
