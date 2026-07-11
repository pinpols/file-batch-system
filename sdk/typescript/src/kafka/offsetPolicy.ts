/**
 * Pure offset-policy helpers for the Kafka adapter (NO kafkajs dependency, so
 * they are unit-testable without a broker or the optional kafkajs install).
 *
 * These encode the two rules the real adapter's `eachMessage` acts on:
 *   - a "commit" disposition advances PAST the record (offset + 1, kafkajs
 *     commit semantics) — accepted task, or a poison record commit-skipped.
 *   - "withhold" / "backpressure" seek BACK to the record's own offset and pause,
 *     so the message is redelivered and NO later commit can cross this offset
 *     (§A / §1.9 — parity with the Go/Java seek+pause semantics).
 */

import type { MessageDisposition } from "../client/consumer.ts";

export type OffsetAction =
  | { type: "commit"; topic: string; partition: number; offset: string }
  | {
      type: "seek-pause";
      topic: string;
      partition: number;
      offset: string;
      reason: MessageDisposition;
    };

/**
 * Map a {@link MessageDisposition} to the concrete Kafka offset action for a
 * record at (topic, partition, offset). "commit" → commit offset+1; everything
 * else → seek back to this offset + pause (the offset is never crossed).
 */
export function offsetAction(
  disposition: MessageDisposition,
  topic: string,
  partition: number,
  offset: string,
): OffsetAction {
  if (disposition === "commit") {
    // kafkajs commits the NEXT offset to read (i.e. current + 1).
    return { type: "commit", topic, partition, offset: (BigInt(offset) + 1n).toString() };
  }
  return { type: "seek-pause", topic, partition, offset, reason: disposition };
}

/**
 * Resolve the subscribe `fromBeginning` flag. Defaults to `false` (latest) to
 * match the other four SDKs' `auto.offset.reset=latest`; `fromBeginning:true`
 * would replay the whole topic on every new consumer group. An explicit value
 * always wins.
 */
export function resolveFromBeginning(fromBeginning?: boolean): boolean {
  return fromBeginning ?? false;
}
