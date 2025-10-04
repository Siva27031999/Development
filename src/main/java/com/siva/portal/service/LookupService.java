// service/LookupService.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LookupService {

  private static final Logger log = LoggerFactory.getLogger(LookupService.class);

  public static final String DEFAULT_KEY = "default";

  private final LookupValueDao dao;
  private final Map<String, InMemoryIndex> indices = new ConcurrentHashMap<>();
  private final WriteBehindStore store;

  /**
   * If you are using Spring Data index auto-creation (@Indexed + spring.data.mongodb.auto-index-creation=true),
   * set this to true to skip manual ensureIndexes().
   */
  private final boolean relyOnSpringAutoIndexes = false; // flip to true if you use @Indexed

  public LookupService(LookupValueDao dao) {
    this.dao = dao;
    // Background, non-blocking write-behind (drains on its own executor)
    this.store = new WriteBehindStore(dao, this::snapshotForKey);
  }

  @PostConstruct
  public void init() {
    // 1) Ensure indexes (unless you rely on Spring’s auto-indexer)
    if (!relyOnSpringAutoIndexes) {
      try {
        dao.ensureIndexes();
      } catch (Exception e) {
        // Don’t fail startup—log and proceed with in-memory only
        log.warn("LookupService: ensureIndexes() failed; continuing in in-memory-only mode for now", e);
      }
    }

    // 2) Try to preload the DEFAULT bucket; tolerate DB down/malformed docs
    try {
      ensureIndexLoaded(DEFAULT_KEY); // this will attempt preload under the hood
      log.info("LookupService: preload complete for key={}", DEFAULT_KEY);
    } catch (Exception e) {
      // Don’t fail startup—users can still interact; write-behind will sync later
      log.warn("LookupService: preload failed for key={}, continuing with empty in-memory index", DEFAULT_KEY, e);
      indices.putIfAbsent(DEFAULT_KEY, new InMemoryIndex());
    }

    // No explicit drain() calls here—WriteBehindStore runs on its own executor.
  }

  /* ======================== Public API ======================== */

  public List<String> suggest(String key, String prefix, int limit) {
    return ensureIndexLoaded(safeKey(key)).suggest(prefix, limit);
  }

  /**
   * Suggest values where the normalized value contains the given query anywhere (substring match),
   * preserving the same ranking as prefix suggestions. Used for broader lookup matches.
   */
  public List<String> suggestContains(String key, String query, int limit) {
    return ensureIndexLoaded(safeKey(key)).suggestContains(query, limit);
  }

  public void addIfAbsent(String key, String value) {
    var idx = ensureIndexLoaded(safeKey(key));
    String norm = InMemoryIndex.normalize(value);
    if (norm.isEmpty()) return;

    // immediate in-memory effect for UX
    if (!idx.containsNorm(norm)) {
      idx.upsertValue(value);
      // enqueue only; background thread persists
      store.enqueueAdd(safeKey(key), value);
    } else {
      idx.upsertValue(value); // bumps frequency locally
      store.enqueueTouch(safeKey(key), value); // optional: coalesced persist later
    }
  }

  public void deleteValue(String key, String value) {
    var idx = ensureIndexLoaded(safeKey(key));
    String norm = InMemoryIndex.normalize(value);
    idx.removeValue(norm);                  // update memory immediately
    store.enqueueDelete(safeKey(key), value); // persist later in background
  }

  /* ======================== Internals ======================== */

  private String safeKey(String key) {
    return (key == null || key.isBlank()) ? DEFAULT_KEY : key;
  }

  /**
   * Lazily loads an index for the key. On first load:
   *  - tries to fetch the bucket from DAO (tolerant parsing in DAO),
   *  - preloads (value, freq, createdAt) into the trie,
   *  - if DB is down or empty, returns a fresh empty index.
   */
  private InMemoryIndex ensureIndexLoaded(String key) {
    return indices.computeIfAbsent(key, k -> {
      var idx = new InMemoryIndex();
      try {
        var bucketOpt = dao.getBucket(k);
        if (bucketOpt.isPresent()) {
          var bucket = bucketOpt.get();
          // Convert DAO DocValue -> InMemoryIndex.Entry with createdAt/frequency preserved
          List<InMemoryIndex.Entry> entries = new ArrayList<>();
          for (var dv : bucket.values()) {
            entries.add(new InMemoryIndex.Entry(dv.value(), dv.norm(), Math.max(1, dv.frequency()), dv.createdAt()));
          }
          idx.preloadEntries(entries); // preserves createdAt & freq
          log.info("LookupService: loaded {} entries for key={}", entries.size(), k);
        } else {
          // Optional: fallback legacy preload via dao.findAllValues(k)
          var legacy = dao.findAllValues(k);
          if (!legacy.isEmpty()) {
            idx.preloadAll(legacy);
            log.info("LookupService: legacy preload {} entries for key={}", legacy.size(), k);
          }
        }
      } catch (Exception e) {
        // Tolerate DB issues; keep empty index so the app is usable
        log.warn("LookupService: failed loading bucket for key={}, using empty in-memory index", k, e);
      }
      return idx;
    });
  }

  /** For write-behind: produce the authoritative list to persist for a key. */
  private List<LookupValueDao.DocValue> snapshotForKey(String key) {
    var idx = indices.get(key);
    if (idx == null) return List.of();
    var snap = idx.snapshot();
    List<LookupValueDao.DocValue> out = new ArrayList<>(snap.size());
    for (var e : snap) {
      out.add(new LookupValueDao.DocValue(e.value(), e.norm(), e.frequency(), e.createdAt()));
    }
    return out;
  }
}
