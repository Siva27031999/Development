// service/LookupService.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LookupService {

  private final LookupValueDao dao;
  private final Map<String, InMemoryIndex> indices = new ConcurrentHashMap<>();
  private final WriteBehindStore store;

  public static final String DEFAULT_KEY = "default";

  public LookupService(LookupValueDao dao) {
    this.dao = dao;
    this.store = new WriteBehindStore(dao, this::snapshotForKey);
  }

  @PostConstruct
  public void init() {
    dao.ensureIndexes();

    // Try load default bucket; if missing, auto-migrate legacy docs into a bucket
    var bucketOpt = dao.getBucket(DEFAULT_KEY);
    if (bucketOpt.isEmpty()) {
      // read all legacy values (no 'key' field docs)
      var legacyValues = dao.findAllValues(DEFAULT_KEY); // already returns values from bucket or legacy
      if (!legacyValues.isEmpty()) {
        // immediately load into in-memory index
        ensureIndexLoaded(DEFAULT_KEY).preloadAll(legacyValues);
        // persist as a single bucket document (best effort)
        try {
          store.enqueueTouch(DEFAULT_KEY, ""); // just to trigger a drain later if you like
          dao.upsertBucket(DEFAULT_KEY,
                  ensureIndexLoaded(DEFAULT_KEY).snapshot().stream()
                          .map(e -> new LookupValueDao.DocValue(e.value(), e.norm(), e.frequency(), System.currentTimeMillis()))
                          .toList()
          );
        } catch (Exception ignored) {
          // If Mongo is unavailable, it's fine—your write-behind queue will handle it later.
        }
      }
    } else {
      // bucket exists → preload normally
      ensureIndexLoaded(DEFAULT_KEY);
    }
  }

  private InMemoryIndex ensureIndexLoaded(String key) {
    return indices.computeIfAbsent(key, k -> {
      var idx = new InMemoryIndex();
      var values = dao.findAllValues(k);
      idx.preloadAll(values);
      return idx;
    });
  }

  public List<String> suggest(String key, String prefix, int limit) {
    return ensureIndexLoaded(key).suggest(prefix, limit);
  }

  public void addIfAbsent(String key, String value) {
    var idx = ensureIndexLoaded(key);
    String norm = InMemoryIndex.normalize(value);
    if (norm.isEmpty()) return;
    if (!idx.containsNorm(norm)) {
      idx.upsertValue(value);                 // instant availability
      store.enqueueAdd(key, value);           // async write-behind
    } else {
      idx.upsertValue(value);                 // bump freq locally
      store.enqueueTouch(key, value);         // optional: persist freq bump eventually
    }
  }

  public void deleteValue(String key, String value) {
    var idx = ensureIndexLoaded(key);
    String norm = InMemoryIndex.normalize(value);
    // remove from in-memory index (safe if absent)
    idx.removeValue(norm);
    // queue delete op (best effort)
    store.enqueueDelete(key, value);
  }

  /** Called by WriteBehindStore to get the current authoritative list to persist */
  private List<LookupValueDao.DocValue> snapshotForKey(String key) {
    var idx = ensureIndexLoaded(key);
    var snapshot = idx.snapshot(); // returns (value,norm,frequency,createdAt)
    var now = Instant.now().toEpochMilli();
    var out = new ArrayList<LookupValueDao.DocValue>(snapshot.size());
    snapshot.forEach(e ->
            out.add(new LookupValueDao.DocValue(
                    e.value(), e.norm(), e.frequency(), e.createdAt() == 0 ? now : e.createdAt()))
    );
    return out;
  }
}

