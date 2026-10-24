package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Creates the managed one-Job Workflow in the caller's transaction. */
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

  public WorkflowDefinitionEntity provision(JobDefinitionEntity job) {
    WorkflowDefinitionEntity existing =
        workflowDefinitionMapper.selectBySingleJobDefinitionId(job.getTenantId(), job.getId());
    if (EmptyChecks.isNotNull(existing)) {
      return existing;
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
    String calendarCode = job.getCalendarCode();
    if (EmptyChecks.isNull(calendarCode) || EmptyChecks.isBlank(calendarCode)) {
      calendarCode = DEFAULT_CALENDAR_CODE;
      if (EmptyChecks.isNull(businessCalendarMapper.selectActiveByTenantAndCalendarCode(
          job.getTenantId(), calendarCode))) {
        throw BizException.of(
            ResultCode.NOT_FOUND,
            ResultCode.NOT_FOUND.detailKey(),
            "default business calendar not found for tenant " + job.getTenantId());
      }
    }
    workflow.setCalendarCode(calendarCode);
    workflow.setDependsOnJobCode(job.getDependsOnJobCode());
    workflowDefinitionMapper.insert(workflow);

    upsertNode(job, workflow.getId(), START, "开始", "START", 0, null);
    upsertNode(job, workflow.getId(), JOB, job.getJobName(), "JOB", 1, job.getJobCode());
    upsertNode(job, workflow.getId(), END, "结束", "END", 2, null);
    upsertEdge(job, workflow.getId(), START, JOB);
    upsertEdge(job, workflow.getId(), JOB, END);
    return workflow;
  }

  private void upsertNode(
      JobDefinitionEntity job,
      Long workflowId,
      String nodeCode,
      String nodeName,
      String nodeType,
      int nodeOrder,
      String relatedJobCode) {
    WorkflowNodeUpsertParam node = new WorkflowNodeUpsertParam();
    node.setTenantId(job.getTenantId());
    node.setWorkflowDefinitionId(workflowId);
    node.setNodeCode(nodeCode);
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
