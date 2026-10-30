package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 批量日补跑逐项状态；网络回执身份必须由调用方核验后才可确认。 */
final class BatchDayCatchUpProgress {

  private BatchDayCatchUpProgress() {}

  enum ItemStatus {
    PENDING,
    IN_FLIGHT,
    UNKNOWN,
    ACCEPTED
  }

  record Item(String jobCode, String sourceKey, ItemStatus status, String referenceNo) {

    Item {
      if (!Texts.hasText(jobCode) || !Texts.hasText(sourceKey) || EmptyChecks.isNull(status)) {
        throw new IllegalArgumentException("invalid catch-up item identity");
      }
      if ((status == ItemStatus.ACCEPTED && !Texts.hasText(referenceNo))
          || (status != ItemStatus.ACCEPTED && EmptyChecks.isNotNull(referenceNo))) {
        throw new IllegalArgumentException("invalid catch-up receipt");
      }
    }

    static Item pending(String jobCode, String sourceKey) {
      return new Item(jobCode, sourceKey, ItemStatus.PENDING, null);
    }
  }

  static List<Item> claim(List<Item> items, String sourceKey) {
    validate(items);
    int firstPending = -1;
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      if (item.status() == ItemStatus.IN_FLIGHT || item.status() == ItemStatus.UNKNOWN) {
        throw new IllegalStateException("catch-up item outcome is unresolved");
      }
      if (firstPending < 0 && item.status() == ItemStatus.PENDING) {
        firstPending = i;
      }
    }
    if (firstPending < 0 || !items.get(firstPending).sourceKey().equals(sourceKey)) {
      throw new IllegalStateException("catch-up item is not next");
    }
    return replace(items, firstPending, ItemStatus.IN_FLIGHT, null);
  }

  static List<Item> markUnknown(List<Item> items, String sourceKey) {
    validate(items);
    int index = find(items, sourceKey);
    if (items.get(index).status() != ItemStatus.IN_FLIGHT) {
      throw new IllegalStateException("catch-up item is not in flight");
    }
    return replace(items, index, ItemStatus.UNKNOWN, null);
  }

  /** 调用前必须核验回执与原请求的租户、来源键和完整业务载荷一致。 */
  static List<Item> accept(List<Item> items, String sourceKey, String referenceNo) {
    validate(items);
    if (!Texts.hasText(referenceNo)) {
      throw new IllegalArgumentException("catch-up receipt is required");
    }
    int index = find(items, sourceKey);
    ItemStatus status = items.get(index).status();
    if (status != ItemStatus.IN_FLIGHT && status != ItemStatus.UNKNOWN) {
      throw new IllegalStateException("catch-up item was not submitted");
    }
    return replace(items, index, ItemStatus.ACCEPTED, referenceNo);
  }

  static boolean isComplete(List<Item> items) {
    validate(items);
    return items.stream().allMatch(item -> item.status() == ItemStatus.ACCEPTED);
  }

  private static int find(List<Item> items, String sourceKey) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).sourceKey().equals(sourceKey)) {
        return i;
      }
    }
    throw new IllegalArgumentException("unknown catch-up source key");
  }

  private static List<Item> replace(
      List<Item> items, int index, ItemStatus status, String referenceNo) {
    List<Item> updated = new ArrayList<>(items);
    Item old = items.get(index);
    updated.set(index, new Item(old.jobCode(), old.sourceKey(), status, referenceNo));
    return List.copyOf(updated);
  }

  private static void validate(List<Item> items) {
    if (EmptyChecks.isNull(items)) {
      throw new IllegalArgumentException("catch-up items are required");
    }
    Set<String> sourceKeys = new HashSet<>();
    Set<String> jobs = new HashSet<>();
    boolean sawUnconfirmed = false;
    for (Item item : items) {
      if (EmptyChecks.isNull(item)
          || !sourceKeys.add(item.sourceKey())
          || !jobs.add(item.jobCode())) {
        throw new IllegalArgumentException("duplicate or missing catch-up item identity");
      }
      if (item.status() == ItemStatus.ACCEPTED && sawUnconfirmed) {
        throw new IllegalArgumentException("catch-up results are out of order");
      }
      if (item.status() == ItemStatus.PENDING) {
        sawUnconfirmed = true;
      } else if (item.status() == ItemStatus.IN_FLIGHT || item.status() == ItemStatus.UNKNOWN) {
        if (sawUnconfirmed) {
          throw new IllegalArgumentException("multiple or out-of-order unresolved catch-up items");
        }
        sawUnconfirmed = true;
      }
    }
  }
}
