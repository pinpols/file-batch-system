package com.example.batch.worker.dispatchs.infrastructure;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test: DispatchReceiptPollScheduler.poll() guarded behavior.
 */
class DispatchReceiptPollSchedulerTest {

    private DispatchReceiptPollProperties properties;
    private FileDispatchRepository fileDispatchRepository;
    private PlatformFileRuntimeRepository runtimeRepository;
    private DispatchReceiptPollScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new DispatchReceiptPollProperties();
        fileDispatchRepository = mock(FileDispatchRepository.class);
        runtimeRepository = mock(PlatformFileRuntimeRepository.class);
        scheduler = new DispatchReceiptPollScheduler(
                properties,
                fileDispatchRepository,
                new ObjectMapper(),
                runtimeRepository,
                new SimpleMeterRegistry()
        );
        scheduler.initializeMeters();
    }

    @Test
    void shouldSkipPollingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.poll();

        verify(fileDispatchRepository, never()).listPendingReceiptPolls(anyInt());
    }

    @Test
    void shouldDoNothingWhenNoPendingRows() {
        properties.setEnabled(true);
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of());

        scheduler.poll();

        verify(fileDispatchRepository).listPendingReceiptPolls(anyInt());
        verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
    }

    @Test
    void shouldSkipRowWhenFileIdIsNull() {
        properties.setEnabled(true);
        Map<String, Object> row = Map.of(
                "tenant_id", "t1",
                "channel_code", "CH1",
                "external_request_id", "req-001"
                // file_id intentionally absent
        );
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of(row));

        scheduler.poll();

        verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
    }

    @Test
    void shouldSkipRowWhenChannelCodeIsBlank() {
        properties.setEnabled(true);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("tenant_id", "t1");
        row.put("file_id", 100L);
        row.put("channel_code", "");
        row.put("external_request_id", "req-001");
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of(row));

        scheduler.poll();

        verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
    }

    @Test
    void shouldSkipRowWhenExternalRequestIdIsNull() {
        properties.setEnabled(true);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("tenant_id", "t1");
        row.put("file_id", 200L);
        row.put("channel_code", "CH1");
        row.put("external_request_id", null);
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of(row));

        scheduler.poll();

        verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
    }

    @Test
    void shouldSkipRowWhenChannelNotFound() {
        properties.setEnabled(true);
        Map<String, Object> row = Map.of(
                "tenant_id", "t1",
                "file_id", 300L,
                "channel_code", "NONEXISTENT",
                "external_request_id", "req-999"
        );
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of(row));
        when(fileDispatchRepository.loadChannel("t1", "NONEXISTENT")).thenReturn(Map.of());

        scheduler.poll();

        verify(fileDispatchRepository).loadChannel("t1", "NONEXISTENT");
        verify(fileDispatchRepository, never()).markAcked(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void shouldSkipRowWhenPollUrlNotConfigured() {
        properties.setEnabled(true);
        Map<String, Object> row = Map.of(
                "tenant_id", "t1",
                "file_id", 400L,
                "channel_code", "CH1",
                "external_request_id", "req-123"
        );
        when(fileDispatchRepository.listPendingReceiptPolls(anyInt())).thenReturn(List.of(row));
        // Channel config without receipt_poll_url
        when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(Map.of(
                "channel_code", "CH1",
                "channel_type", "API"
        ));

        scheduler.poll();

        verify(fileDispatchRepository, never()).markAcked(anyString(), anyLong(), anyString(), anyString());
    }
}
