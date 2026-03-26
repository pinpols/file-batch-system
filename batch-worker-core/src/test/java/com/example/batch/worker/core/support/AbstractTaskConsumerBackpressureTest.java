package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

class AbstractTaskConsumerBackpressureTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void shouldPauseWhenPermitsExhausted_thenResumeAfterRelease() throws Exception {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("test-listener")).thenReturn(container);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);

        TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
        when(executor.execute(org.mockito.Mockito.any(), org.mockito.Mockito.anyString())).thenAnswer(inv -> {
            entered.countDown();
            allowFinish.await();
            return new WorkerExecutionResult("1", true, "ok");
        });

        WorkerRuntimeFacade runtimeFacade = mock(WorkerRuntimeFacade.class);
        when(runtimeFacade.start(org.mockito.Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        AbstractTaskConsumer consumer = new AbstractTaskConsumer(registry) {
            @Override
            protected AbstractWorkerLoop workerLoop() {
                return new AbstractWorkerLoop(runtimeFacade) {
                    @Override
                    protected WorkerConfiguration workerConfiguration() {
                        return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
                    }

                    @Override
                    protected String workerGroup() {
                        return "test";
                    }

                    @Override
                    protected int workerPort() {
                        return 0;
                    }
                };
            }

            @Override
            protected WorkerConfiguration workerConfiguration() {
                return AbstractTaskConsumerBackpressureTest.this.workerConfiguration();
            }

            @Override
            protected TaskDispatchExecutor taskDispatchExecutor() {
                return executor;
            }

            @Override
            protected String listenerId() {
                return "test-listener";
            }
        };

        // Force permits = 1
        ReflectionTestUtils.setField(consumer, "maxConcurrentTasks", 1);

        String msg = JsonUtils.toJson(new TaskDispatchMessage(
                "v1",
                "t1",
                1L,
                null,
                1L,
                null,
                null,
                "EXECUTION",
                1,
                "IMPORT",
                null,
                null,
                null,
                "{}",
                "tr",
                "k",
                null
        ));

        Future<?> f1 = pool.submit(() -> {
            ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg);
        });

        assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Second call should pause and return immediately (no permit)
        when(container.isPauseRequested()).thenReturn(false).thenReturn(true);
        Future<?> f2 = pool.submit(() -> ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg));
        f2.get();
        verify(container, times(1)).pause();

        // When first finishes, it should release and resume
        when(container.isPauseRequested()).thenReturn(true);
        allowFinish.countDown();
        f1.get();
        verify(container, times(1)).resume();
    }

    private WorkerConfiguration workerConfiguration() {
        return new WorkerConfiguration() {
            @Override
            public String workerCode() {
                return "w1";
            }

            @Override
            public String workerType() {
                return "IMPORT";
            }

            @Override
            public String tenantId() {
                return "t1";
            }

            @Override
            public Long heartbeatIntervalMillis() {
                return 1000L;
            }

            @Override
            public String topic() {
                return "t";
            }

            @Override
            public String consumerGroupId() {
                return "g";
            }
        };
    }
}

