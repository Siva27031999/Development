// repo/LookupValueDao.java
package com.siva.portal.repo;

import java.util.List;
import java.util.Optional;

public interface LookupValueDao {
  void ensureIndexes();
  List<String> findAllValues(String key);
  void upsertBucket(String key, List<DocValue> values) throws Exception;
  Optional<Bucket> getBucket(String key);

  record DocValue(String value, String norm, int frequency, long createdAt) {}
  record Bucket(String key, List<DocValue> values) {}
}
