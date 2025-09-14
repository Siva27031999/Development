// repo/LookupValueRepository.java
package com.siva.portal.repo;

import com.siva.portal.model.LookupValue;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LookupValueRepository extends MongoRepository<LookupValue, String> {
  Optional<LookupValue> findByNorm(String norm);
  boolean existsByNorm(String norm);
}
