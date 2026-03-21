package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRouter;
import com.example.batch.orchestrator.domain.pipeline.PipelineContext;
import com.example.batch.orchestrator.domain.pipeline.PipelineDefinition;
import com.example.batch.orchestrator.domain.pipeline.PipelineExecutionResult;
import com.example.batch.orchestrator.domain.pipeline.PipelineExecutor;
import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;
import com.example.batch.orchestrator.domain.pipeline.StepResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DefaultPipelineExecutor implements PipelineExecutor {

    private final StepRegistry stepRegistry;
    private final WorkerRouter workerRouter;

    public DefaultPipelineExecutor(StepRegistry stepRegistry, WorkerRouter workerRouter) {
        this.stepRegistry = stepRegistry;
        this.workerRouter = workerRouter;
    }

    @Override
    public PipelineExecutionResult execute(PipelineContext context) {
        PipelineDefinition definition = context.getPipelineDefinition();
        List<StepResult> results = new ArrayList<>();
        if (definition == null || definition.getSteps() == null) {
            return emptyResult(context, results);
        }
        definition.getSteps().stream()
                .filter(step -> Boolean.TRUE.equals(step.getEnabled()))
                .sorted(Comparator.comparing(step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
                .forEach(stepDefinition -> results.add(executeStep(context, definition, stepDefinition)));
        context.setStepResults(results);
        return emptyResult(context, results);
    }

    private StepResult executeStep(PipelineContext context, PipelineDefinition definition, com.example.batch.orchestrator.domain.pipeline.StepDefinition stepDefinition) {
        WorkerRouteModel workerRouteModel = resolveWorkerRoute(context, definition, stepDefinition);
        Optional<Step> step = stepRegistry.find(stepDefinition.getStepCode());
        if (step.isEmpty()) {
            return new StepResult();
        }
        return step.get().execute(context, workerRouteModel);
    }

    private WorkerRouteModel resolveWorkerRoute(PipelineContext context, PipelineDefinition definition, com.example.batch.orchestrator.domain.pipeline.StepDefinition stepDefinition) {
        WorkerRouteModel route = context.getDefaultWorkerRoute();
        if (route == null) {
            route = workerRouter.route(context.getTenantId(), definition.getPipelineCode(), stepDefinition.getStepCode());
        }
        if (route == null) {
            route = new WorkerRouteModel();
        }
        if (stepDefinition.getWorkerType() != null && !stepDefinition.getWorkerType().isBlank()) {
            WorkerRouteModel override = copyRoute(route);
            override.setWorkerType(stepDefinition.getWorkerType());
            override.setCapabilityTags(stepDefinition.getCapabilityTags());
            override.setResourceProfile(stepDefinition.getResourceProfile());
            return override;
        }
        return route;
    }

    private WorkerRouteModel copyRoute(WorkerRouteModel source) {
        WorkerRouteModel target = new WorkerRouteModel();
        target.setWorkerId(source.getWorkerId());
        target.setWorkerType(source.getWorkerType());
        target.setCapabilityTags(source.getCapabilityTags());
        target.setResourceProfile(source.getResourceProfile());
        target.setPriority(source.getPriority());
        target.setAvailable(source.getAvailable());
        return target;
    }

    private PipelineExecutionResult emptyResult(PipelineContext context, List<StepResult> results) {
        PipelineExecutionResult result = new PipelineExecutionResult();
        return result;
    }
}
