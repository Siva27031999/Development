// repo/LookupValueDao.java
package com.siva.portal.repo;

import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface LookupValueDao {
  boolean existsByNorm(String norm);
  void save(String value, String norm) throws Exception;
  List<String> findAllValues();
  void ensureIndexes();
}
