package com.example.batch.console.infrastructure.realtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ConsoleRealtimeMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeSubscriptions = new AtomicInteger();

    public ConsoleRealtimeMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("batch.console.realtime.subscriptions.active", activeSubscriptions);
    }

    public void incrementSubscriptions() {
        activeSubscriptions.incrementAndGet();
    }

    public void decrementSubscriptions() {
        activeSubscriptions.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void recordReplayDelivered(String stream, int eventCount) {
        if (eventCount <= 0) {
            return;
        }
        counter("batch.console.realtime.replay.events", stream).increment(eventCount);
    }

    public void recordReplayCursorMiss(String stream) {
        counter("batch.console.realtime.replay.cursor.miss", stream).increment();
    }

    public void recordReplayDecodeFailure(String stream) {
        counter("batch.console.realtime.replay.decode.failures", stream).increment();
    }

    public void recordPubSubDecodeFailure() {
        counter("batch.console.realtime.pubsub.decode.failures").increment();
    }

    public void recordPubSubHandleFailure(String stream, String eventType) {
        counter("batch.console.realtime.pubsub.handle.failures", stream, eventType).increment();
    }

    private Counter counter(String name) {
        return meterRegistry.counter(name);
    }

    private Counter counter(String name, String stream) {
        return meterRegistry.counter(name, "stream", normalize(stream));
    }

    private Counter counter(String name, String stream, String eventType) {
        return meterRegistry.counter(name, "stream", normalize(stream), "eventType", normalize(eventType));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
