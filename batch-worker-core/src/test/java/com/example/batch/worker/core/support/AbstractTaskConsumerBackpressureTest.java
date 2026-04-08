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
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

            @Override
            protected DeadLetterPublisher deadLetterPublisher() {
                return null;
            }
        };

        // 强制 permits = 1
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

        // 第二次调用应触发 pause 并立即返回（无可用 permit）
        when(container.isPauseRequested()).thenReturn(false).thenReturn(true);
        Future<?> f2 = pool.submit(() -> ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg));
        f2.get();
        verify(container, times(1)).pause();

        // 第一个任务完成后，应释放 permit 并恢复消费
        when(container.isPauseRequested()).thenReturn(true);
        allowFinish.countDown();
        f1.get();
        verify(container, times(1)).resume();
    }

    @Test
    void shouldExposeRunModeInMdcDuringConsumption() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
        WorkerRuntimeFacade runtimeFacade = mock(WorkerRuntimeFacade.class);
        when(runtimeFacade.start(org.mockito.Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
        when(executor.execute(org.mockito.Mockito.any(), org.mockito.Mockito.anyString())).thenAnswer(inv -> {
            assertThat(MDC.get("runMode")).isEqualTo("RETRY");
            return new WorkerExecutionResult("1", true, "ok");
        });

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

            @Override
            protected DeadLetterPublisher deadLetterPublisher() {
                return null;
            }
        };

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
                "{\"run_mode\":\"RETRY\"}",
                "tr",
                "k",
                null
        ));

        ReflectionTestUtils.invokeMethod(consumer, "doConsume", msg);

        assertThat(MDC.get("runMode")).isNull();
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
