package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformFileRuntimeRepositoryTest {

    @Test
    void ensurePipelineDefinitionShouldUseJobCodeForDefinitionTable() {
        PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
        PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);

        when(mapper.selectLatestPipelineDefinitionId(anyMap())).thenReturn(null);
        when(mapper.insertPipelineDefinition(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> paramMap = invocation.getArgument(0);
            paramMap.put("id", 4601L);
            return 1;
        });
        when(mapper.selectPipelineStepDefinitions(anyMap())).thenReturn(List.of());

        Long pipelineDefinitionId = repository.ensurePipelineDefinition(
                "tenant-a",
                "job-a",
                "IMPORT",
                "worker-a",
                "demo",
                List.<PipelineStepTemplate>of()
        );

        assertThat(pipelineDefinitionId).isEqualTo(4601L);
        verify(mapper).selectLatestPipelineDefinitionId(argThat(params ->
                "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && !params.containsKey("pipelineCode")));
        verify(mapper).insertPipelineDefinition(argThat(params ->
                "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && "job-a".equals(params.get("pipelineName"))
                        && !params.containsKey("pipelineCode")));
    }

    @Test
    void createPipelineInstanceShouldUseJobCodeForInstanceTable() {
        PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
        PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);

        when(mapper.insertPipelineInstance(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> paramMap = invocation.getArgument(0);
            paramMap.put("id", 5301L);
            return 1;
        });

        Long pipelineInstanceId = repository.createPipelineInstance(
                "tenant-a",
                4601L,
                "job-a",
                "IMPORT",
                5201L,
                4001L,
                "VALIDATE",
                "trace-a"
        );

        assertThat(pipelineInstanceId).isEqualTo(5301L);
        verify(mapper).insertPipelineInstance(argThat(params ->
                "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && !params.containsKey("pipelineCode")));
    }
}
