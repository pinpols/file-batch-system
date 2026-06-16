/**
 * @batch/worker-sdk — TypeScript BYO worker SDK.
 *
 * Phase 1: pure decision core + protocol types + shared constants.
 * Phase 2: runtime engine — transport, schedulers, lifecycle FSM, consumer
 * pipeline, sensitive-data validator, handler SPI, and a fake-platform testkit.
 * Verifiable end-to-end against a fake platform; the real kafkajs client is a
 * documented future thin layer behind the `Consumer` interface.
 */

// Phase 1
export * from "./constants.ts";
export * from "./protocol.ts";
export * from "./decide.ts";

// Phase 2 — runtime engine
export * from "./client/transport.ts";
export * from "./client/scheduler.ts";
export * from "./client/lifecycle.ts";
export * from "./client/consumer.ts";
export * from "./client/sensitive.ts";
export * from "./client/handler.ts";
export * from "./client/checkpoint.ts";
export * from "./client/testkit.ts";
