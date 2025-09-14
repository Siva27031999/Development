// service/InMemoryIndex.java
package com.siva.portal.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prefix-indexed in-memory store for fast typeahead.
 * - Trie for O(k) prefix traversal
 * - HashSet for O(1) existence checks
 * - freq map for ranking
 * - createdAt map to retain first-seen time
 */
public class InMemoryIndex {

  /* ---------- Trie ---------- */
  static class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord;
    String word; // original-cased value
  }

  private final TrieNode root = new TrieNode();

  /* ---------- Aux structures ---------- */
  private final Set<String> normSet = ConcurrentHashMap.newKeySet();      // presence
  private final Map<String, Integer> freq = new ConcurrentHashMap<>();    // ranking
  private final Map<String, Long> createdAt = new ConcurrentHashMap<>();  // first insert time (epoch millis)

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /* ---------- Normalization ---------- */
  public static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  /* ---------- Public API ---------- */

  /** True if normalized string already present. */
  public boolean containsNorm(String norm) {
    return normSet.contains(norm);
  }

  /** Upsert with current time as createdAt (only on first insert). */
  public void upsertValue(String value) {
    upsertValue(value, System.currentTimeMillis());
  }

  /** Upsert with explicit createdAt (used when preloading from DB). */
  public void upsertValue(String value, long createdAtMillis) {
    final String norm = normalize(value);
    if (norm.isEmpty()) return;

    lock.writeLock().lock();
    try {
      if (!normSet.add(norm)) {
        // Already present → bump frequency
        freq.merge(norm, 1, Integer::sum);
        // Do not overwrite createdAt; preserve first-seen time
        return;
      }

      // First time we see this value → insert into Trie
      TrieNode node = root;
      for (char ch : norm.toCharArray()) {
        node = node.children.computeIfAbsent(ch, k -> new TrieNode());
      }
      node.isWord = true;
      node.word = value;

      // Book-keeping
      freq.put(norm, 1);
      // Only set createdAt if missing
      this.createdAt.putIfAbsent(norm, createdAtMillis);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Suggest values for a prefix. Ranked by:
   *  1) frequency DESC
   *  2) normalized lexicographic ASC
   *
   * Uses top-k selection (min-heap) to avoid sorting large subtrees.
   */
  public List<String> suggest(String prefix, int limit) {
    final int k = Math.max(1, limit);

    lock.readLock().lock();
    try {
      TrieNode node = root;
      String normPre = normalize(prefix);
      for (char ch : normPre.toCharArray()) {
        node = node.children.get(ch);
        if (node == null) return Collections.emptyList();
      }

      // Min-heap keeps the "worst" item on top (lowest freq, or lexicographically larger)
      Comparator<String> worseFirst = (a, b) -> {
        String na = normalize(a), nb = normalize(b);
        int fa = freq.getOrDefault(na, 0);
        int fb = freq.getOrDefault(nb, 0);
        if (fa != fb) return Integer.compare(fa, fb); // smaller freq = worse
        // For equal freq, we want lexicographically larger to be worse (so it gets popped)
        return -na.compareTo(nb);
      };
      PriorityQueue<String> topk = new PriorityQueue<>(k, worseFirst);

      // DFS traversal of the prefix subtree; push candidates into top-k heap
      collectTopK(node, topk, k);

      // Pop heap into result list in the desired order: best → worst
      List<String> result = new ArrayList<>(topk.size());
      while (!topk.isEmpty()) result.add(topk.poll());
      // Heap pops worst-first; reverse to best-first
      Collections.reverse(result);

      // Always return a copy; never expose a subList backed by another list
      return new ArrayList<>(result);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Remove a value (by normalized form). Safe if absent. */
  public void removeValue(String norm) {
    if (norm == null || norm.isEmpty()) return;

    lock.writeLock().lock();
    try {
      if (!normSet.remove(norm)) return; // not present

      // Remove from Trie
      removeFromTrie(root, norm, 0);

      // Cleanup metadata
      freq.remove(norm);
      createdAt.remove(norm);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Snapshot entries for persistence. */
  public static record Entry(String value, String norm, int frequency, long createdAt) {}

  public List<Entry> snapshot() {
    lock.readLock().lock();
    try {
      List<String> all = new ArrayList<>();
      dfsCollect(root, all);

      List<Entry> out = new ArrayList<>(all.size());
      for (String v : all) {
        String n = normalize(v);
        int f = freq.getOrDefault(n, 1);
        long c = createdAt.getOrDefault(n, 0L);
        out.add(new Entry(v, n, f, c));
      }
      return out;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Preload a batch of values with unknown createdAt (uses "now"). */
  public void preloadAll(Collection<String> values) {
    if (values == null || values.isEmpty()) return;
    long now = System.currentTimeMillis();
    for (String v : values) upsertValue(v, now);
  }

  /** Preload entries with explicit createdAt/frequency (when you have them). */
  public void preloadEntries(Collection<Entry> entries) {
    if (entries == null || entries.isEmpty()) return;
    for (Entry e : entries) {
      // Preserve provided createdAt and frequency
      final String norm = normalize(e.value);
      lock.writeLock().lock();
      try {
        if (!normSet.add(norm)) {
          // If already exists, reconcile frequency (keep max) and keep original createdAt
          freq.merge(norm, e.frequency, Math::max);
        } else {
          // Insert into trie
          TrieNode node = root;
          for (char ch : norm.toCharArray()) {
            node = node.children.computeIfAbsent(ch, k -> new TrieNode());
          }
          node.isWord = true;
          node.word = e.value;

          freq.put(norm, Math.max(1, e.frequency));
          createdAt.putIfAbsent(norm, e.createdAt > 0 ? e.createdAt : System.currentTimeMillis());
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  /* ---------- Internal helpers ---------- */

  private void collectTopK(TrieNode node, PriorityQueue<String> topk, int k) {
    if (node.isWord && node.word != null) {
      if (topk.size() < k) {
        topk.offer(node.word);
      } else {
        // Offer then trim (heap comparator handles "worse" at head)
        topk.offer(node.word);
        if (topk.size() > k) topk.poll();
      }
    }
    for (TrieNode child : node.children.values()) {
      collectTopK(child, topk, k);
    }
  }

  private void dfsCollect(TrieNode node, List<String> out) {
    if (node.isWord && node.word != null) out.add(node.word);
    for (TrieNode child : node.children.values()) dfsCollect(child, out);
  }

  /**
   * Remove the path for the given normalized string; prune empty nodes.
   * @return true if this node became empty and can be pruned by its parent
   */
  private boolean removeFromTrie(TrieNode node, String norm, int i) {
    if (i == norm.length()) {
      if (node.isWord) { node.isWord = false; node.word = null; }
    } else {
      char ch = norm.charAt(i);
      TrieNode child = node.children.get(ch);
      if (child != null) {
        boolean childEmpty = removeFromTrie(child, norm, i + 1);
        if (childEmpty) node.children.remove(ch);
      }
    }
    return !node.isWord && node.children.isEmpty();
  }
}

