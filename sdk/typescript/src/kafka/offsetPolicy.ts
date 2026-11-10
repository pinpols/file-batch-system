/**
 * Pure offset-policy helpers for the Kafka adapter (NO kafkajs dependency, so
 * they are unit-testable without a broker or the optional kafkajs install).
 *
 * These encode the offset rules the real adapter's `eachMessage` acts on:
 *   - a "commit" disposition advances PAST the record (offset + 1, kafkajs
 *     commit semantics) — accepted task, or a poison record commit-skipped —
 *     UNLESS the partition already has a lower withheld offset (see below).
 *   - "withhold" 不提交该记录；适配器保留提交上限并暂停当前分区，等待后重读。
 *   - a "backpressure" disposition seeks BACK to the record's own offset and
 *     pauses the partition — that pause is TEMPORARY, resumed when a slot frees
 *     (§1.5 / §2).
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
 *   - "withhold"     → 保留该 offset，由适配器暂停分区并等待重读
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
    const nextOffset = BigInt(offset) + 1n;
    const nextOffsetText = nextOffset.toString();
    return { type: "commit", topic, partition, offset: nextOffsetText };
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
  const recordOffsetValue = BigInt(recordOffset);
  const currentCeilingValue = BigInt(current);
  return recordOffsetValue < currentCeilingValue ? recordOffset : current;
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
  const recordOffsetValue = BigInt(recordOffset);
  const ceilingValue = BigInt(ceiling);
  return recordOffsetValue >= ceilingValue;
}

/**
 * 解析订阅起点；新消费组默认从最早保留位置读取，显式配置优先。
 */
export function resolveFromBeginning(fromBeginning?: boolean): boolean {
  return fromBeginning ?? true;
}
