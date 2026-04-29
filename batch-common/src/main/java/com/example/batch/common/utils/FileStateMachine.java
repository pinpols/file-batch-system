package com.example.batch.common.utils;

import com.example.batch.common.enums.FileStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.EnumSet;
import java.util.Map;

/**
 * 文件状态机，维护 {@link com.example.batch.common.enums.FileStatus} 的合法流转规则。 初始状态仅允许 {@code RECEIVED} 和
 * {@code GENERATED}； 违反状态约束时抛出 {@link com.example.batch.common.exception.BizException}（{@code
 * STATE_CONFLICT}）。 同状态转换（current == next）视为幂等操作，不抛异常。
 */
public final class FileStateMachine {

  private static final EnumSet<FileStatus> INITIAL_STATES =
      EnumSet.of(FileStatus.RECEIVED, FileStatus.GENERATED);

  private static final Map<FileStatus, EnumSet<FileStatus>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(
              FileStatus.RECEIVED,
              EnumSet.of(FileStatus.PARSING, FileStatus.FAILED, FileStatus.ARCHIVED)),
          Map.entry(FileStatus.PARSING, EnumSet.of(FileStatus.PARSED, FileStatus.FAILED)),
          Map.entry(FileStatus.PARSED, EnumSet.of(FileStatus.VALIDATED, FileStatus.FAILED)),
          Map.entry(FileStatus.VALIDATED, EnumSet.of(FileStatus.LOADED, FileStatus.FAILED)),
          Map.entry(FileStatus.LOADED, EnumSet.of(FileStatus.ARCHIVED, FileStatus.FAILED)),
          Map.entry(
              FileStatus.GENERATED,
              EnumSet.of(FileStatus.DISPATCHING, FileStatus.ARCHIVED, FileStatus.FAILED)),
          Map.entry(FileStatus.DISPATCHING, EnumSet.of(FileStatus.DISPATCHED, FileStatus.FAILED)),
          Map.entry(
              FileStatus.DISPATCHED,
              EnumSet.of(FileStatus.DISPATCHING, FileStatus.ARCHIVED, FileStatus.FAILED)),
          Map.entry(FileStatus.ARCHIVED, EnumSet.of(FileStatus.DELETED)),
          Map.entry(
              FileStatus.FAILED,
              EnumSet.of(
                  FileStatus.PARSING,
                  FileStatus.GENERATED,
                  FileStatus.DISPATCHING,
                  FileStatus.ARCHIVED)),
          Map.entry(FileStatus.DELETED, EnumSet.noneOf(FileStatus.class)));

  private FileStateMachine() {}

  public static void assertInitialStatus(String statusCode) {
    FileStatus status = parse(statusCode);
    if (!INITIAL_STATES.contains(status)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "unsupported initial file status: " + statusCode);
    }
  }

  public static void assertTransition(String currentStatusCode, String nextStatusCode) {
    FileStatus current = parse(currentStatusCode);
    FileStatus next = parse(nextStatusCode);
    if (current == next) {
      return;
    }
    EnumSet<FileStatus> allowed =
        TRANSITIONS.getOrDefault(current, EnumSet.noneOf(FileStatus.class));
    if (!allowed.contains(next)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "illegal file status transition: " + current.name() + " -> " + next.name());
    }
  }

  public static boolean canTransition(String currentStatusCode, String nextStatusCode) {
    try {
      assertTransition(currentStatusCode, nextStatusCode);
      return true;
    } catch (BizException exception) {
      return false;
    }
  }

  private static FileStatus parse(String statusCode) {
    FileStatus status = FileStatus.fromCode(statusCode);
    Guard.require(status != null, "file status is required");
    return status;
  }
}
