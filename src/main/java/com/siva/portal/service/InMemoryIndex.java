// service/InMemoryIndex.java
package com.siva.portal.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class InMemoryIndex {

  static class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord;
    String word; // original value
  }

  private final TrieNode root = new TrieNode();
  private final Set<String> normSet = ConcurrentHashMap.newKeySet(); // de-dupe
  private final Map<String, Integer> freq = new ConcurrentHashMap<>();// ranking
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  public static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase();
  }

  public boolean containsNorm(String norm) {
    return normSet.contains(norm);
  }

  public void upsertValue(String value) {
    String norm = normalize(value);
    lock.writeLock().lock();
    try {
      if (!normSet.add(norm)) {
        // already exists → bump frequency
        freq.merge(norm, 1, Integer::sum);
        return;
      }
      // insert into Trie
      TrieNode node = root;
      for (char ch : norm.toCharArray()) {
        node = node.children.computeIfAbsent(ch, k -> new TrieNode());
      }
      node.isWord = true;
      node.word = value; // keep the nice-cased original
      freq.put(norm, 1);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public List<String> suggest(String prefix, int limit) {
    String normPre = normalize(prefix);
    lock.readLock().lock();
    try {
      TrieNode node = root;
      for (char ch : normPre.toCharArray()) {
        node = node.children.get(ch);
        if (node == null) return Collections.emptyList();
      }
      List<String> results = new ArrayList<>();
      dfs(node, results);
      // rank by frequency desc, then lexicographically
      results.sort((a, b) -> {
        String na = normalize(a), nb = normalize(b);
        int fa = freq.getOrDefault(na, 0), fb = freq.getOrDefault(nb, 0);
        if (fa != fb) return Integer.compare(fb, fa);
        return na.compareTo(nb);
      });
      if (results.size() > limit) return results.subList(0, limit);
      return results;
    } finally {
      lock.readLock().unlock();
    }
  }

  private void dfs(TrieNode node, List<String> out) {
    if (node.isWord && node.word != null) out.add(node.word);
    for (TrieNode child : node.children.values()) dfs(child, out);
  }

  public void preloadAll(Collection<String> values) {
    for (String v : values) upsertValue(v);
  }
}
