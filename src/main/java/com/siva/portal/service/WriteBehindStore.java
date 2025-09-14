// service/WriteBehindStore.java
package com.siva.portal.service;

import com.siva.portal.repo.LookupValueDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class WriteBehindStore {

  private final LookupValueDao dao;
  private final Queue<String> pending = new ConcurrentLinkedQueue<>();

  @Autowired
  public WriteBehindStore(@Qualifier("mongoLookupValueDao") LookupValueDao dao) {
    this.dao = dao;
  }

  public void enqueue(String value) {
    pending.offer(value);
    drain();
  }

  /** Try to persist everything currently pending; stop at first failure. */
  public void drain() {
    while (true) {
      String v = pending.peek();
      if (v == null) return;
      String norm = InMemoryIndex.normalize(v);
      try {
        if (!dao.existsByNorm(norm)) {
          dao.save(v, norm);
        } else {
          // already exists in DB, OK
        }
        pending.poll(); // success/exists → remove
      } catch (Exception e) {
        // DB might be down; keep item in queue & stop draining now
        return;
      }
    }
  }
}
