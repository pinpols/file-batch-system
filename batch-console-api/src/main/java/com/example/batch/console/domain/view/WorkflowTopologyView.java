package com.example.batch.console.domain.view;

import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.console.domain.entity.WorkflowRunEntity;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class WorkflowTopologyView {

    private WorkflowDefinitionEntity workflowDefinition;
    private List<WorkflowNodeEntity> nodes = new ArrayList<>();
    private List<WorkflowEdgeEntity> edges = new ArrayList<>();
    private List<WorkflowRunEntity> workflowRuns = new ArrayList<>();
    private List<WorkflowNodeRunEntity> nodeRuns = new ArrayList<>();
}
