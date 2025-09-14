// service/WriteBehindStore.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

public class WriteBehindStore {

  private final LookupValueDao dao;
  private final Function<String, List<LookupValueDao.DocValue>> snapshotSupplier;

  private enum Type { ADD, DELETE, TOUCH }
  private record Op(Type type, String key, String value) {}

  private final Queue<Op> pending = new ConcurrentLinkedQueue<>();

  public WriteBehindStore(LookupValueDao dao,
                          Function<String, List<LookupValueDao.DocValue>> snapshotSupplier) {
    this.dao = dao;
    this.snapshotSupplier = snapshotSupplier;
  }

  public void enqueueAdd(String key, String value)   { enqueue(new Op(Type.ADD, key, value)); }
  public void enqueueDelete(String key, String value){ enqueue(new Op(Type.DELETE, key, value)); }
  public void enqueueTouch(String key, String value) { enqueue(new Op(Type.TOUCH, key, value)); }

  private void enqueue(Op op) {
    pending.offer(op);
    drain();
  }

  /** Batch ops per key → read-modify-write the bucket with upsert. */
  public void drain() {
    // Drain a snapshot of queue to avoid infinite loops when errors happen
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < 512; i++) { // hard cap per drain to bound work
      Op op = pending.poll();
      if (op == null) break;
      ops.add(op);
    }
    if (ops.isEmpty()) return;

    Map<String, List<Op>> byKey = ops.stream().collect(Collectors.groupingBy(Op::key));
    for (var entry : byKey.entrySet()) {
      String key = entry.getKey();
      List<Op> keyOps = entry.getValue();
      try {
        // Start from current in-memory snapshot (authoritative for fast updates)
        List<LookupValueDao.DocValue> snap = snapshotSupplier.apply(key);
        // Build index by norm
        Map<String, LookupValueDao.DocValue> map = new LinkedHashMap<>();
        for (var dv : snap) map.put(dv.norm(), dv);

        for (Op op : keyOps) {
          String norm = InMemoryIndex.normalize(op.value());
          switch (op.type) {
            case ADD -> {
              var existing = map.get(norm);
              if (existing == null) {
                map.put(norm, new LookupValueDao.DocValue(op.value(), norm, 1, System.currentTimeMillis()));
              } else {
                map.put(norm, new LookupValueDao.DocValue(existing.value(), norm, existing.frequency() + 1, existing.createdAt()));
              }
            }
            case TOUCH -> {
              var existing = map.get(norm);
              if (existing != null) {
                map.put(norm, new LookupValueDao.DocValue(existing.value(), norm, existing.frequency() + 1, existing.createdAt()));
              }
            }
            case DELETE -> map.remove(norm);
          }
        }

        dao.upsertBucket(key, new ArrayList<>(map.values()));
      } catch (Exception e) {
        // On any error (e.g., DB down), push this key's ops back to the head in order
        // preserving semantics: they'll retry on the next enqueue/drain.
        // (Note: using addAll to the FRONT would reorder; we re-queue them to tail)
        pending.addAll(keyOps);
        // Stop now to keep earlier-guaranteed semantics
        return;
      }
    }

    // If queue still has items, we leave them for next enqueue call.
  }
}

