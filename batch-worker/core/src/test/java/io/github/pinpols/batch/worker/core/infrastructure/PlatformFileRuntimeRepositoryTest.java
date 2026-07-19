package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class PlatformFileRuntimeRepositoryTest {

  @Test
  void facadeShouldPreserveConstructorAndTransactionBoundary() throws NoSuchMethodException {
    assertThat(PlatformFileRuntimeRepository.class.getConstructor(PlatformFileRuntimeMapper.class))
        .isNotNull();
    assertThat(
            PlatformFileRuntimeRepository.class
                .getMethod("createFileRecord", FileRecordParam.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
  }

  @Test
  void ensurePipelineDefinitionShouldUseJobCodeForDefinitionTable() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);

    when(mapper.selectLatestPipelineDefinitionId(anyMap())).thenReturn(null);
    when(mapper.insertPipelineDefinition(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> paramMap = invocation.getArgument(0);
              paramMap.put("id", 4601L);
              return 1;
            });
    when(mapper.selectPipelineStepDefinitions(anyMap())).thenReturn(List.of());

    Long pipelineDefinitionId =
        repository.ensurePipelineDefinition(
            "tenant-a", "job-a", "IMPORT", "worker-a", "demo", List.<PipelineStepTemplate>of());

    assertThat(pipelineDefinitionId).isEqualTo(4601L);
    verify(mapper)
        .selectLatestPipelineDefinitionId(
            argThat(
                params ->
                    "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && !params.containsKey("pipelineCode")));
    verify(mapper)
        .insertPipelineDefinition(
            argThat(
                params ->
                    "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && "job-a".equals(params.get("pipelineName"))
                        && !params.containsKey("pipelineCode")));
  }

  @Test
  void createPipelineInstanceShouldUseJobCodeForInstanceTable() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);

    when(mapper.insertPipelineInstance(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> paramMap = invocation.getArgument(0);
              paramMap.put("id", 5301L);
              return 1;
            });

    Long pipelineInstanceId =
        repository.createPipelineInstance(
            new PlatformFileRuntimeRepository.CreatePipelineInstanceParam(
                "tenant-a", 4601L, "job-a", "IMPORT", 5201L, 4001L, "VALIDATE", "trace-a"));

    assertThat(pipelineInstanceId).isEqualTo(5301L);
    verify(mapper)
        .insertPipelineInstance(
            argThat(
                params ->
                    "tenant-a".equals(params.get("tenantId"))
                        && "job-a".equals(params.get("jobCode"))
                        && !params.containsKey("pipelineCode")));
  }

  @Test
  void createFileRecordShouldPreserveDedupPrecheck() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);
    when(mapper.selectMaxFileGenerationNo(anyMap())).thenReturn(2);
    when(mapper.selectFileRecordByStoragePath(anyMap()))
        .thenReturn(Map.of("id", 77L, "checksum_value", ""));

    Long fileId =
        repository.createFileRecord(
            FileRecordParam.builder()
                .tenantId("tenant-a")
                .fileCode("file-a")
                .fileCategory("INPUT")
                .fileName("orders.csv")
                .fileFormatType("CSV")
                .storageType("MINIO")
                .storageBucket("batch")
                .storagePath("incoming/orders.csv")
                .sourceType("UPLOAD")
                .fileStatus("RECEIVED")
                .build());

    assertThat(fileId).isEqualTo(77L);
    verify(mapper)
        .markHistoricalFileNotLatest(
            argThat(
                params ->
                    "tenant-a".equals(params.get("tenantId"))
                        && "file-a".equals(params.get("fileCode"))));
    verify(mapper, never()).insertFileRecord(anyMap());
  }

  @Test
  void createFileRecordShouldPreserveGenerationParameters() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRuntimeRepository repository = new PlatformFileRuntimeRepository(mapper);
    when(mapper.selectMaxFileGenerationNo(anyMap())).thenReturn(2);
    when(mapper.insertFileRecord(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> params = invocation.getArgument(0);
              params.put("id", 78L);
              return 1;
            });

    Long fileId =
        repository.createFileRecord(
            FileRecordParam.builder()
                .tenantId("tenant-a")
                .fileCode("file-a")
                .fileCategory("INPUT")
                .fileName("orders.csv")
                .fileFormatType("CSV")
                .checksumValue("sha256-value")
                .storageType("MINIO")
                .storagePath("incoming/orders.csv")
                .sourceType("UPLOAD")
                .fileStatus("RECEIVED")
                .build());

    assertThat(fileId).isEqualTo(78L);
    verify(mapper)
        .insertFileRecord(
            argThat(
                params ->
                    Integer.valueOf(3).equals(params.get("fileGenerationNo"))
                        && "v3".equals(params.get("fileVersion"))));
  }
}
