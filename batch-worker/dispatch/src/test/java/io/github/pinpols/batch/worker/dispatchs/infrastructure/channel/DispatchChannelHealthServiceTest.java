package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelConfigView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("渠道健康服务:上下文关闭后停止探测,以及失败退避直接采用落库返回的失败计数")
@ExtendWith(OutputCaptureExtension.class)
class DispatchChannelHealthServiceTest {

    private static DispatchChannelConfigView probeChannel(String code, Object config) {
        return new DispatchChannelConfigView(
                null,
                "tenant-a",
                code,
                null,
                "API",
                "https://user:probe-url-marker@example.test/upload",
                null,
                config,
                null,
                null,
                true,
                null,
                null,
                false);
    }

    private DispatchChannelHealthRepository repository;
    private DispatchChannelHealthService service;
    private DispatchChannelHealthProperties properties;
    private CountDownLatch blockedProbeRelease;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(DispatchChannelHealthRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BatchObjectStore> objectStoreProvider = mock(ObjectProvider.class);
        when(objectStoreProvider.getIfAvailable()).thenReturn(null);
        properties = new DispatchChannelHealthProperties();
        DispatchCircuitBreakerProperties circuitBreakerProperties = new DispatchCircuitBreakerProperties();
        meterRegistry = new SimpleMeterRegistry();
        service = new DispatchChannelHealthService(
                repository,
                properties,
                circuitBreakerProperties,
                new S3StorageProperties(),
                new BatchSecurityProperties(),
                new ObjectMapper(),
                meterRegistry,
                objectStoreProvider);
        service.init();
    }

    @AfterEach
    void tearDown() {
        if (blockedProbeRelease != null) {
            blockedProbeRelease.countDown();
        }
        service.shutdownProbeExecutor();
    }

    @Test
    @DisplayName("八个探针同时阻塞时,调度线程在同一个超时窗口内继续处理下一批")
    void shouldNotAccumulateTimeoutForConcurrentBlockedProbes(CapturedOutput output) throws Exception {
        blockedProbeRelease = new CountDownLatch(1);
        PGobject configJson = new PGobject();
        configJson.setType("jsonb");
        configJson.setValue("{\"sftp_password\":\"probe-secret-marker\"}");
        List<DispatchChannelConfigView> channels = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            channels.add(probeChannel("channel-" + index, configJson));
        }
        when(repository.findEnabledProbeChannels(anyList(), anyInt(), any())).thenReturn(channels);
        when(repository.findHealth(eq("tenant-a"), any())).thenAnswer(invocation -> {
            String channelCode = invocation.getArgument(1);
            if ("channel-9".equals(channelCode)) {
                return DispatchChannelHealthSnapshot.builder()
                        .nextProbeAt(Instant.now().plusSeconds(60))
                        .build();
            }
            try {
                blockedProbeRelease.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("probe interrupted", interrupted);
            }
            return null;
        });

        assertTimeoutPreemptively(Duration.ofSeconds(12), service::probeConfiguredChannels);

        verify(repository).findHealth("tenant-a", "channel-9");
        assertThat(meterRegistry
                        .get("batch.dispatch.channel.probe.failures")
                        .gauge()
                        .value())
                .isEqualTo(8.0);
        assertThat(output.getOut()).doesNotContain("probe-secret-marker", "probe-url-marker");
    }

    @Test
    @DisplayName("探针任务抛异常时只记录一次失败并结束当批等待")
    void shouldCountProbeExceptionOnce() {
        when(repository.findEnabledProbeChannels(anyList(), anyInt(), any()))
                .thenReturn(List.of(probeChannel("channel-a", null)));
        when(repository.findHealth("tenant-a", "channel-a"))
                .thenThrow(new IllegalStateException("probe storage unavailable"));

        assertTimeoutPreemptively(Duration.ofSeconds(2), service::probeConfiguredChannels);

        assertThat(meterRegistry
                        .get("batch.dispatch.channel.probe.failures")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("上下文关闭后再触发探测时,不再查询任何启用探测的渠道")
    void shouldSkipProbeChannels_whenContextClosed() {
        service.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

        service.probeConfiguredChannels();

        verify(repository, never()).findEnabledProbeChannels(anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("记录投递失败时直接用落库返回的失败次数重算退避,不再回读健康快照")
    void shouldUseReturnedFailureCount_whenRecordingDispatchFailure() {
        when(repository.upsertFailureAndBump(any())).thenReturn(4);
        Map<String, Object> channel =
                Map.of("tenant_id", "tenant-a", "channel_code", "channel-a", "channel_type", "API");

        service.recordDispatchOutcome(channel, false, "timeout", null);

        verify(repository).recalcBackoff(any(), eq(4));
        verify(repository, never()).findHealth("tenant-a", "channel-a");
    }

    @Test
    @DisplayName("单轮预算耗尽后停止后续批次,剩余到期渠道留到下一轮")
    void shouldStopRemainingBatches_whenRoundBudgetExhausted(CapturedOutput output) {
        properties.setProbeIntervalMillis(100);
        List<DispatchChannelConfigView> channels = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            channels.add(probeChannel("channel-" + index, null));
        }
        when(repository.findEnabledProbeChannels(anyList(), anyInt(), any())).thenReturn(channels);
        when(repository.findHealth(eq("tenant-a"), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return DispatchChannelHealthSnapshot.builder()
                    .nextProbeAt(Instant.now().plusSeconds(60))
                    .build();
        });

        assertTimeoutPreemptively(Duration.ofSeconds(5), service::probeConfiguredChannels);

        assertThat(output.getOut()).contains("round budget exhausted");
        verify(repository, never()).findHealth("tenant-a", "channel-9");
    }

    @Test
    @DisplayName("线程全部占用且队列已满时,提交被拒后停止本轮并保留剩余渠道")
    void shouldStopRound_whenProbeQueueRejectsSubmission(CapturedOutput output) throws Exception {
        blockedProbeRelease = new CountDownLatch(1);
        ExecutorService initializedExecutor = (ExecutorService) ReflectionTestUtils.getField(service, "probeExecutor");
        assertThat(initializedExecutor).isNotNull();
        initializedExecutor.shutdownNow();
        ReflectionTestUtils.setField(
                service,
                "probeExecutor",
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        new ThreadPoolExecutor.AbortPolicy()));
        List<DispatchChannelConfigView> channels = List.of(
                probeChannel("channel-1", null), probeChannel("channel-2", null), probeChannel("channel-3", null));
        when(repository.findEnabledProbeChannels(anyList(), anyInt(), any())).thenReturn(channels);
        when(repository.findHealth(eq("tenant-a"), any())).thenAnswer(invocation -> {
            blockedProbeRelease.await();
            return DispatchChannelHealthSnapshot.builder()
                    .nextProbeAt(Instant.now().plusSeconds(60))
                    .build();
        });

        service.probeConfiguredChannels();

        assertThat(output.getOut()).contains("probe queue is full");
    }
}
