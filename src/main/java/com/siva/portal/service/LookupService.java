// service/LookupService.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Service
public class LookupService {
  private final LookupValueDao dao;
  private final InMemoryIndex index;
  private final WriteBehindStore store;

  @Autowired
  public LookupService(@Qualifier("mongoLookupValueDao") LookupValueDao dao) {
    this.dao = dao;
    this.index = new InMemoryIndex();
    this.store = new WriteBehindStore(dao);
  }

  @PostConstruct
  public void init() {
    // Ensure indexes every start (safe if already present)
    dao.ensureIndexes();
    // Preload values into Trie
    var all = dao.findAllValues();
    index.preloadAll(all);
  }

  public List<String> suggest(String prefix, int limit) {
    return index.suggest(prefix, limit);
  }

  public void addIfAbsent(String value) {
    String norm = InMemoryIndex.normalize(value);
    if (norm.isEmpty()) return;
    if (!index.containsNorm(norm)) {
      index.upsertValue(value);   // instant availability
      store.enqueue(value);       // best-effort persistence
    } else {
      index.upsertValue(value);   // bump frequency locally
    }
  }
}
