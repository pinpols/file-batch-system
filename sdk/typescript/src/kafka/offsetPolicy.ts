/**
 * Pure offset-policy helpers for the Kafka adapter (NO kafkajs dependency, so
 * they are unit-testable without a broker or the optional kafkajs install).
 *
 * These encode the offset rules the real adapter's `eachMessage` acts on:
 *   - a "commit" disposition advances PAST the record (offset + 1, kafkajs
 *     commit semantics) — accepted task, or a poison record commit-skipped —
 *     UNLESS the partition already has a lower withheld offset (see below).
 *   - a "withhold" disposition (rejected schema / foreign tenant / not-for-us)
 *     does NOT commit and does NOT pause the partition: it records the record's
 *     own offset as the partition's commit CEILING and keeps consuming. No later
 *     commit on that partition may cross the ceiling, so the withheld record is
 *     redelivered on the next rebalance/restart while subsequent records still
 *     flow (no head-of-line block). This mirrors the Go SDK's `committable`
 *     ceiling exactly; Java/Python/Rust use the same per-partition invariant.
 *   - a "backpressure" disposition seeks BACK to the record's own offset and
 *     pauses the partition — that pause is TEMPORARY, resumed when a slot frees
 *     (§1.5 / §2). This is the only disposition that pauses.
 */

import type { MessageDisposition } from "../client/consumer.ts";

export type OffsetAction =
  | { type: "commit"; topic: string; partition: number; offset: string }
  | { type: "withhold"; topic: string; partition: number; offset: string }
  | {
      type: "seek-pause";
      topic: string;
      partition: number;
      offset: string;
      reason: MessageDisposition;
    };

/**
 * Map a {@link MessageDisposition} to the concrete Kafka offset action for a
 * record at (topic, partition, offset).
 *   - "commit"       → commit offset+1 (advance past this record)
 *   - "withhold"     → record this offset as the partition's commit ceiling and
 *                      KEEP consuming (never committed → redelivered later)
 *   - "backpressure" → seek back to this offset + pause (temporary, resumable)
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
  if (disposition === "withhold") {
    // ceiling is the record's OWN offset — a later commit must never cross it.
    return { type: "withhold", topic, partition, offset };
  }
  return { type: "seek-pause", topic, partition, offset, reason: disposition };
}

/**
 * Lower a partition's withheld ceiling to include `recordOffset`, keeping the
 * LOWEST offset ever withheld on that partition (pure; string offsets are 64-bit
 * safe via BigInt). Returns the new ceiling.
 */
export function loweredCeiling(current: string | undefined, recordOffset: string): string {
  if (current === undefined) return recordOffset;
  return BigInt(recordOffset) < BigInt(current) ? recordOffset : current;
}

/**
 * Whether committing a record at `recordOffset` would cross the partition's
 * withheld ceiling. A commit advances PAST the record, so any record at or after
 * the withheld offset must NOT be committed (its offset would silently skip the
 * withheld one). Pure — mirrors the Go SDK's `committable` drop rule
 * (`m.Offset >= ceil`).
 */
export function commitBlockedByWithheld(
  ceiling: string | undefined,
  recordOffset: string,
): boolean {
  if (ceiling === undefined) return false;
  return BigInt(recordOffset) >= BigInt(ceiling);
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
