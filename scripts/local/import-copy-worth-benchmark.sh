#!/usr/bin/env bash
# 判断 IMPORT LOAD 是否值得上 PG COPY 的本地微基准。
#
# 对比对象:
#   1) 当前实现近似路径: PG JDBC PreparedStatement batch + INSERT ... ON CONFLICT DO UPDATE
#      默认 JDBC URL 带 reWriteBatchedInserts=true。
#   2) COPY 路径: CopyManager.copyIn 到临时表,再 INSERT ... SELECT ... ON CONFLICT DO UPDATE merge 到目标表。
#   3) 分区整批替换路径: TRUNCATE 专用目标表后 CopyManager.copyIn 直接追加。
#
# 只使用专用表 biz.import_copy_worth_bench,默认跑完删除。不会读写真实业务表。
#
# 用法:
#   示例: bash scripts/local/import-copy-worth-benchmark.sh
#   示例: ROWS=200000 BATCH_SIZE=5000 bash scripts/local/import-copy-worth-benchmark.sh
#   示例: EXTRA_INDEXES=3 bash scripts/local/import-copy-worth-benchmark.sh
#   示例: KEEP_BENCH_TABLE=1 bash scripts/local/import-copy-worth-benchmark.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

ROWS="${ROWS:-100000}"
BATCH_SIZE="${BATCH_SIZE:-2000}"
EXTRA_INDEXES="${EXTRA_INDEXES:-0}"
DB_URL="${DB_URL:-jdbc:postgresql://$(batch_format_host_port "$PGHOST" "$PGPORT")/${BUSINESS_DB}?reWriteBatchedInserts=true}"
DB_USER="${DB_USER:-$PGUSER}"
DB_PASSWORD="${DB_PASSWORD:-$PGPASSWORD}"
KEEP_BENCH_TABLE="${KEEP_BENCH_TABLE:-0}"

PG_JAR="$(find "${HOME}/.m2/repository/org/postgresql/postgresql" -name 'postgresql-*.jar' | sort | tail -1)"
if [[ -z "${PG_JAR:-}" || ! -f "$PG_JAR" ]]; then
  echo "ERROR: postgresql jdbc jar not found under ~/.m2/repository/org/postgresql/postgresql" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cat >"$TMP_DIR/ImportCopyWorthBenchmark.java" <<'JAVA'
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

public class ImportCopyWorthBenchmark {
  private static final Path SQL_DIR = Path.of(System.getenv("BENCH_SQL_DIR"));

  public static void main(String[] args) throws Exception {
    int rows = Integer.parseInt(System.getenv().getOrDefault("ROWS", "100000"));
    int batchSize = Integer.parseInt(System.getenv().getOrDefault("BATCH_SIZE", "2000"));
    int extraIndexes = Integer.parseInt(System.getenv().getOrDefault("EXTRA_INDEXES", "0"));
    boolean keepTable = "1".equals(System.getenv().getOrDefault("KEEP_BENCH_TABLE", "0"));
    String url = System.getenv("DB_URL");
    String user = System.getenv("DB_USER");
    String password = System.getenv("DB_PASSWORD");

    try (Connection conn = DriverManager.getConnection(url, user, password)) {
      conn.setAutoCommit(false);
      setup(conn, extraIndexes);

      Result batch = runBatchUpsert(conn, rows, batchSize);
      Result copy = runCopyThenMerge(conn, rows);
      Result replace = runCopyDirectReplace(conn, rows);

      double speedup = batch.totalSeconds / copy.totalSeconds;
      double replaceSpeedup = batch.totalSeconds / replace.totalSeconds;
      System.out.printf(Locale.ROOT, "rows=%d batchSize=%d extraIndexes=%d%n", rows, batchSize, extraIndexes);
      System.out.printf(Locale.ROOT, "batch_upsert_total=%.3fs throughput=%.0f rows/s%n",
          batch.totalSeconds, rows / batch.totalSeconds);
      System.out.printf(Locale.ROOT, "copy_stage=%.3fs merge=%.3fs copy_total=%.3fs throughput=%.0f rows/s%n",
          copy.copySeconds, copy.mergeSeconds, copy.totalSeconds, rows / copy.totalSeconds);
      System.out.printf(Locale.ROOT, "copy_vs_batch_speedup=%.2fx%n", speedup);
      System.out.printf(Locale.ROOT, "copy_direct_replace_total=%.3fs throughput=%.0f rows/s speedup=%.2fx%n",
          replace.totalSeconds, rows / replace.totalSeconds, replaceSpeedup);
      System.out.printf(Locale.ROOT, "upsert_copy_decision=%s%n",
          speedup >= 2.0 ? "COPY_WORTH_IMPLEMENTING" : "COPY_NOT_YET_JUSTIFIED");
      System.out.printf(Locale.ROOT, "partition_replace_decision=%s%n",
          replaceSpeedup >= 2.0 ? "COPY_DIRECT_REPLACE_WORTH_IMPLEMENTING" : "COPY_DIRECT_REPLACE_NOT_YET_JUSTIFIED");

      if (!keepTable) {
        try (Statement st = conn.createStatement()) {
          st.execute(sql("drop-table.sql"));
        }
        conn.commit();
      }
    }
  }

  private static void setup(Connection conn, int extraIndexes) throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute(sql("create-schema.sql"));
      st.execute(sql("drop-table.sql"));
      st.execute(sql("create-table.sql"));
      if (extraIndexes >= 1) {
        st.execute(sql("create-index-c01.sql"));
      }
      if (extraIndexes >= 2) {
        st.execute(sql("create-index-c02-n01.sql"));
      }
      if (extraIndexes >= 3) {
        st.execute(sql("create-index-c03-c04.sql"));
      }
      if (extraIndexes > 3) {
        throw new IllegalArgumentException("EXTRA_INDEXES supports 0..3");
      }
    }
    conn.commit();
  }

  private static Result runBatchUpsert(Connection conn, int rows, int batchSize) throws Exception {
    truncate(conn);
    String sql = sql("batch-upsert.sql");
    long t0 = System.nanoTime();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int pending = 0;
      for (int i = 1; i <= rows; i++) {
        bind(ps, i);
        ps.addBatch();
        pending++;
        if (pending >= batchSize) {
          ps.executeBatch();
          pending = 0;
        }
      }
      if (pending > 0) {
        ps.executeBatch();
      }
    }
    conn.commit();
    return Result.total(secondsSince(t0));
  }

  private static Result runCopyThenMerge(Connection conn, int rows) throws Exception {
    truncate(conn);
    try (Statement st = conn.createStatement()) {
      st.execute(sql("create-stage-table.sql"));
    }
    CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
    String copySql = sql("copy-stage.sql");
    long copyT0 = System.nanoTime();
    try (Reader reader = new CsvRowsReader(rows)) {
      copyManager.copyIn(copySql, reader);
    }
    double copySeconds = secondsSince(copyT0);

    String mergeSql = sql("merge-stage.sql");
    long mergeT0 = System.nanoTime();
    try (Statement st = conn.createStatement()) {
      st.executeUpdate(mergeSql);
    }
    conn.commit();
    double mergeSeconds = secondsSince(mergeT0);
    return new Result(copySeconds, mergeSeconds, copySeconds + mergeSeconds);
  }

  private static Result runCopyDirectReplace(Connection conn, int rows) throws Exception {
    truncate(conn);
    CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
    String copySql = sql("copy-target.sql");
    long t0 = System.nanoTime();
    try (Reader reader = new CsvRowsReader(rows)) {
      copyManager.copyIn(copySql, reader);
    }
    conn.commit();
    return Result.total(secondsSince(t0));
  }

  private static void truncate(Connection conn) throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute(sql("truncate-table.sql"));
    }
    conn.commit();
  }

  private static void bind(PreparedStatement ps, int i) throws Exception {
    int p = 1;
    ps.setString(p++, "ta");
    ps.setString(p++, rowKey(i));
    for (int c = 1; c <= 15; c++) {
      ps.setString(p++, "val-" + c + "-" + i);
    }
    ps.setString(p++, longText(i, 300));
    ps.setString(p++, longText(i, 299));
    for (int n = 1; n <= 5; n++) {
      ps.setBigDecimal(p++, BigDecimal.valueOf((i % 1000) + n, 2));
    }
  }

  private static String sql(String name) throws Exception {
    return Files.readString(SQL_DIR.resolve(name));
  }

  private static String csvLine(int i) {
    StringBuilder sb = new StringBuilder(920);
    sb.append("ta,").append(rowKey(i));
    for (int c = 1; c <= 15; c++) {
      sb.append(",val-").append(c).append('-').append(i);
    }
    sb.append(',').append(longText(i, 300));
    sb.append(',').append(longText(i, 299));
    for (int n = 1; n <= 5; n++) {
      sb.append(',').append(String.format(Locale.ROOT, "%.2f", ((i % 1000) + n) / 100.0));
    }
    sb.append('\n');
    return sb.toString();
  }

  private static String rowKey(int i) {
    return "WIDE-" + i;
  }

  private static String longText(int i, int len) {
    String seed = String.format(Locale.ROOT, "%010d", i);
    StringBuilder sb = new StringBuilder(len);
    while (sb.length() < len) {
      sb.append(seed);
    }
    return sb.substring(0, len);
  }

  private static double secondsSince(long t0) {
    return Duration.ofNanos(System.nanoTime() - t0).toNanos() / 1_000_000_000.0;
  }

  private record Result(double copySeconds, double mergeSeconds, double totalSeconds) {
    static Result total(double seconds) {
      return new Result(0, 0, seconds);
    }
  }

  private static final class CsvRowsReader extends Reader {
    private final int rows;
    private int next = 1;
    private String current = "";
    private int offset = 0;
    private boolean closed = false;

    CsvRowsReader(int rows) {
      this.rows = rows;
    }

    @Override
    public int read(char[] cbuf, int off, int len) {
      if (closed) {
        throw new IllegalStateException("reader closed");
      }
      if (next > rows && offset >= current.length()) {
        return -1;
      }
      int written = 0;
      while (written < len) {
        if (offset >= current.length()) {
          if (next > rows) {
            break;
          }
          current = csvLine(next++);
          offset = 0;
        }
        int n = Math.min(len - written, current.length() - offset);
        current.getChars(offset, offset + n, cbuf, off + written);
        offset += n;
        written += n;
      }
      return written == 0 ? -1 : written;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
JAVA

javac -cp "$PG_JAR" "$TMP_DIR/ImportCopyWorthBenchmark.java"
echo "==> import COPY worth benchmark"
echo "    rows=$ROWS batchSize=$BATCH_SIZE url=$DB_URL"
ROWS="$ROWS" \
BATCH_SIZE="$BATCH_SIZE" \
DB_URL="$DB_URL" \
DB_USER="$DB_USER" \
DB_PASSWORD="$DB_PASSWORD" \
KEEP_BENCH_TABLE="$KEEP_BENCH_TABLE" \
BENCH_SQL_DIR="$ROOT/scripts/local/sql/import-copy-benchmark" \
java -Xms256m -Xmx1g -cp "$PG_JAR:$TMP_DIR" ImportCopyWorthBenchmark
