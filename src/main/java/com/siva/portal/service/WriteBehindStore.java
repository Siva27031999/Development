// service/WriteBehindStore.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Write-behind store for bucketed persistence (one Mongo document per lookup 'key').
 * - Non-blocking: drains run on a single-threaded scheduler.
 * - Coalesces ops per key to minimize writes.
 * - Exponential backoff on failure (per key).
 * - Bounded pending sets per key.
 *
 * Requires:
 *  - LookupValueDao.upsertBucket(String key, List<DocValue> values)
 *  - snapshotSupplier.apply(key) -> current in-memory list of DocValue for that key
 */
public class WriteBehindStore {

  /* ---------------------- Tunables ---------------------- */

  private static final int MAX_PENDING_PER_KEY = 2000;  // distinct values (adds+deletes) per key
  private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
  private static final Duration MAX_BACKOFF     = Duration.ofSeconds(30);

  /* ---------------------- Types ---------------------- */

  private enum Type { ADD, DELETE, TOUCH }

  /** Pending ops coalesced per key. */
  private static final class Pending {
    // keep latest original-cased value by norm
    final Map<String, String> addsByNorm = new LinkedHashMap<>();
    final Set<String> deletesNorm = new LinkedHashSet<>();
    // touch acts like a freq bump; we don’t need to store individual touches, since
    // the authoritative list comes from snapshotSupplier (which already bumps locally).
    int attempt = 0;              // backoff attempt counter
    long nextRunEpochMs = 0L;     // next eligible drain time (epoch ms)
  }

  /* ---------------------- State ---------------------- */

  private final LookupValueDao dao;
  private final Function<String, List<LookupValueDao.DocValue>> snapshotSupplier;

  // per-key coalesced state
  private final ConcurrentHashMap<String, Pending> pendingByKey = new ConcurrentHashMap<>();

  // drain orchestration
  private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "lookup-writebehind");
    t.setDaemon(true);
    return t;
  });
  private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

  public WriteBehindStore(LookupValueDao dao,
                          Function<String, List<LookupValueDao.DocValue>> snapshotSupplier) {
    this.dao = dao;
    this.snapshotSupplier = snapshotSupplier;
  }

  /* ---------------------- API ---------------------- */

  public void enqueueAdd(String key, String value) {
    final String norm = InMemoryIndex.normalize(value);
    if (norm.isEmpty()) return;

    Pending p = pendingByKey.computeIfAbsent(key, k -> new Pending());
    synchronized (p) {
      if (p.addsByNorm.size() + p.deletesNorm.size() >= MAX_PENDING_PER_KEY) {
        // Soft guardrail: drop oldest delete if too many; then oldest add if still too many
        if (!p.deletesNorm.isEmpty()) {
          Iterator<String> it = p.deletesNorm.iterator();
          it.next(); it.remove();
        } else if (!p.addsByNorm.isEmpty()) {
          Iterator<String> it = p.addsByNorm.keySet().iterator();
          it.next(); it.remove();
        }
      }
      p.deletesNorm.remove(norm);
      p.addsByNorm.put(norm, value); // keep latest casing
    }
    scheduleDrainSoon();
  }

  public void enqueueDelete(String key, String value) {
    final String norm = InMemoryIndex.normalize(value);
    if (norm.isEmpty()) return;

    Pending p = pendingByKey.computeIfAbsent(key, k -> new Pending());
    synchronized (p) {
      p.addsByNorm.remove(norm);
      if (p.addsByNorm.size() + p.deletesNorm.size() >= MAX_PENDING_PER_KEY) {
        // guardrail like above: prefer to trim deletes first to keep successful adds
        if (!p.deletesNorm.isEmpty()) {
          Iterator<String> it = p.deletesNorm.iterator();
          it.next(); it.remove();
        }
      }
      p.deletesNorm.add(norm);
    }
    scheduleDrainSoon();
  }

  /** Optional hint to persist freq bumps eventually (coalesced). */
  public void enqueueTouch(String key, String value) {
    // We don’t track touches; snapshot already reflects local freq.
    scheduleDrainSoon();
  }

  /* ---------------------- Draining ---------------------- */

  private void scheduleDrainSoon() {
    if (drainScheduled.compareAndSet(false, true)) {
      exec.schedule(this::drainOnce, 100, TimeUnit.MILLISECONDS);
    }
  }

  private void scheduleDrainWithDelay(Duration d) {
    exec.schedule(this::drainOnce, Math.max(50, d.toMillis()), TimeUnit.MILLISECONDS);
  }

  /** One pass over all keys; reschedules itself if work remains. */
  private void drainOnce() {
    try {
      long now = System.currentTimeMillis();
      boolean anyDeferred = false;

      // snapshot keys to avoid concurrent modification
      List<String> keys = new ArrayList<>(pendingByKey.keySet());
      for (String key : keys) {
        Pending p = pendingByKey.get(key);
        if (p == null) continue;

        boolean doneThisKey = applyForKey(key, p, now);
        if (!doneThisKey) anyDeferred = true;
      }

      // If anything deferred, schedule next pass at the shortest nextRun delay
      if (anyDeferred) {
        long nextAt = pendingByKey.values().stream()
                .mapToLong(x -> x.nextRunEpochMs)
                .filter(x -> x > now)
                .min().orElse(now + MAX_BACKOFF.toMillis());
        scheduleDrainWithDelay(Duration.ofMillis(Math.max(100, nextAt - now)));
      }
    } finally {
      // allow future scheduling if nothing is pending or a later pass is scheduled
      drainScheduled.set(false);
      // If we raced and new items arrived while we were draining, queue another pass.
      if (!pendingByKey.isEmpty()) scheduleDrainSoon();
    }
  }

  /**
   * Apply adds/deletes for one key using read-modify-write against the bucket.
   * Returns true if key is fully drained; false if deferred due to backoff.
   */
  private boolean applyForKey(String key, Pending p, long now) {
    synchronized (p) {
      if (p.nextRunEpochMs > now) return false; // backoff window active

      // Build list to persist: start from in-memory snapshot, then apply coalesced ops
      List<LookupValueDao.DocValue> snap = snapshotSupplier.apply(key);
      Map<String, LookupValueDao.DocValue> byNorm = new LinkedHashMap<>(snap.size());
      for (var dv : snap) byNorm.put(dv.norm(), dv);

      // Apply deletes first
      for (String dn : p.deletesNorm) {
        byNorm.remove(dn);
      }
      // Apply adds (if already present, bump frequency by 1)
      for (var e : p.addsByNorm.entrySet()) {
        String norm = e.getKey();
        String val  = e.getValue();
        var cur = byNorm.get(norm);
        if (cur == null) {
          byNorm.put(norm, new LookupValueDao.DocValue(
                  val, norm, 1, System.currentTimeMillis()));
        } else {
          byNorm.put(norm, new LookupValueDao.DocValue(
                  cur.value(), norm, cur.frequency() + 1, cur.createdAt()));
        }
      }

      try {
        dao.upsertBucket(key, new ArrayList<>(byNorm.values()));
        // success → clear pending and reset backoff
        p.addsByNorm.clear();
        p.deletesNorm.clear();
        p.attempt = 0;
        p.nextRunEpochMs = 0L;

        // if nothing left for this key, we can drop the bucket from pending map
        if (pendingIsEmpty(p)) pendingByKey.remove(key, p);
        return true;
      } catch (Exception ex) {
        // failure → exponential backoff for this key
        p.attempt = Math.min(p.attempt + 1, 10);
        long delay = Math.min(
                INITIAL_BACKOFF.multipliedBy(1L << (p.attempt - 1)).toMillis(),
                MAX_BACKOFF.toMillis()
        );
        p.nextRunEpochMs = now + delay;
        return false;
      }
    }
  }

  private boolean pendingIsEmpty(Pending p) {
    return p.addsByNorm.isEmpty() && p.deletesNorm.isEmpty();
  }

  /* ---------------------- Shutdown hook (optional) ---------------------- */

  public void shutdown() {
    exec.shutdown();
  }
}