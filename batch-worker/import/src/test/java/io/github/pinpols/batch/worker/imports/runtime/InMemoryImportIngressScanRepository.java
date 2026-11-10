package io.github.pinpols.batch.worker.imports.runtime;

import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;

/** 扫描器单测用的进度替身；不作为数据库并发和事务证据。 */
final class InMemoryImportIngressScanRepository extends ImportIngressScanRepository {
  private final Map<String, State> states = new HashMap<>();
  private final Map<String, Entry> entries = new HashMap<>();
  private final Map<String, ImportIngressOwnershipRepository.Scope> ownership = new HashMap<>();
  private long nextId;

  InMemoryImportIngressScanRepository() {
    super(
        mock(ImportIngressScanMapper.class),
        mock(ImportIngressOwnershipRepository.class),
        mock(PlatformTransactionManager.class));
  }

  void declare(ImportIngressOwnershipRepository.Scope scope) {
    ownership.put(scope.scopeId(), scope);
  }

  @Override
  public Optional<ImportIngressOwnershipRepository.Scope> findActiveOwnership(String scopeId) {
    ImportIngressOwnershipRepository.Scope scope = ownership.get(scopeId);
    return scope != null && "ACTIVE".equals(scope.state()) ? Optional.of(scope) : Optional.empty();
  }

  @Override
  public List<ImportIngressOwnershipRepository.Scope> listProtectedOwnership(String storageId) {
    return ownership.values().stream()
        .filter(scope -> storageId.equals(scope.storageId()))
        .filter(scope -> "ACTIVE".equals(scope.state()) || "RETIRED".equals(scope.state()))
        .toList();
  }

  @Override
  public Optional<Lease> claim(
      String storageId,
      String scopeId,
      String bucket,
      String prefix,
      String configDigest,
      long pollIntervalMillis) {
    State state = states.computeIfAbsent(scopeId, ignored -> new State());
    return Optional.of(state.progress.toBuilder()
        .storageId(storageId)
        .storageFence(1)
        .scopeId(scopeId)
        .owner(UUID.randomUUID())
        .fence(1)
        .build());
  }

  @Override
  public void release(Lease lease) {}

  @Override
  public boolean fenced(Lease lease, Runnable action) {
    action.run();
    return true;
  }

  @Override
  public void observe(Lease lease, String digest, ObjectSummary object, boolean manifest) {
    String key = lease.scopeId() + ":" + digest;
    Entry old = entries.get(key);
    boolean changed = old == null
        || old.object.size() != object.size()
        || !Objects.equals(old.object.etag(), object.etag());
    long id = old == null ? ++nextId : old.id;
    Instant first = changed ? Instant.now() : old.first;
    Entry entry = new Entry(
        lease.scopeId(),
        id,
        object,
        first,
        lease.cycle(),
        lease.windowId(),
        manifest,
        changed ? null : old.waitReason);
    entries.put(key, entry);
  }

  @Override
  public List<Observed> manifestPage(Lease lease, int limit) {
    return entries.values().stream()
        .filter(e -> e.scope.equals(lease.scopeId())
            && e.window == lease.windowId()
            && e.id > lease.afterObjectId()
            && e.manifest)
        .sorted(Comparator.comparingLong(e -> e.id))
        .limit(limit)
        .map(Entry::observed)
        .toList();
  }

  @Override
  public List<Observed> applyPage(Lease lease, int limit) {
    return entries.values().stream()
        .filter(e -> e.scope.equals(lease.scopeId())
            && e.id > lease.applyAfterId()
            && e.id <= lease.applyUpperId())
        .sorted(Comparator.comparingLong(e -> e.id))
        .limit(limit)
        .map(Entry::observed)
        .toList();
  }

  @Override
  public long maxObjectId(Lease lease) {
    return entries.values().stream()
        .filter(e -> e.scope.equals(lease.scopeId()))
        .mapToLong(e -> e.id)
        .max()
        .orElse(0);
  }

  @Override
  public Optional<Observed> findObject(Lease lease, String digest) {
    Entry entry = entries.get(lease.scopeId() + ":" + digest);
    return entry == null ? Optional.empty() : Optional.of(entry.observed());
  }

  @Override
  public boolean setManifestWaitReason(Lease lease, long objectId, String reason) {
    boolean changed = entries.values().stream()
        .anyMatch(entry -> entry.id == objectId
            && entry.scope.equals(lease.scopeId())
            && !Objects.equals(entry.waitReason, reason));
    entries.replaceAll((key, entry) -> entry.id == objectId
        ? new Entry(
            entry.scope,
            entry.id,
            entry.object,
            entry.first,
            entry.cycle,
            entry.window,
            entry.manifest,
            reason)
        : entry);
    return changed;
  }

  @Override
  public void removeObject(Lease lease, long objectId) {
    entries.values().removeIf(entry -> entry.id == objectId && entry.scope.equals(lease.scopeId()));
  }

  @Override
  public int cleanOldObjects(Lease lease, int limit) {
    List<String> keys = entries.entrySet().stream()
        .filter(
            e -> e.getValue().scope.equals(lease.scopeId()) && e.getValue().cycle < lease.cycle())
        .sorted(Comparator.comparingLong(e -> e.getValue().id))
        .limit(limit)
        .map(Map.Entry::getKey)
        .toList();
    keys.forEach(entries::remove);
    return keys.size();
  }

  @Override
  public void advance(Lease lease, String phase, String marker, long after, long cycle) {
    saveProgress(lease.toBuilder()
        .phase(phase)
        .listMarker(marker)
        .afterObjectId(after)
        .cycle(cycle)
        .build());
  }

  @Override
  public void saveProgress(Lease lease) {
    states.get(lease.scopeId()).progress = lease;
  }

  private static final class State {
    private Lease progress = Lease.builder()
        .phase("DISCOVER")
        .cycle(1)
        .revisitBudget(1)
        .revisitRemaining(1)
        .nextLane("FORWARD")
        .build();
  }

  private record Entry(
      String scope,
      long id,
      ObjectSummary object,
      Instant first,
      long cycle,
      long window,
      boolean manifest,
      String waitReason) {
    Observed observed() {
      return new Observed(
          id,
          object.key(),
          object.size(),
          object.etag(),
          object.lastModified(),
          first,
          Instant.now());
    }
  }
}
