// repo/MongoLookupValueDao.java
package com.siva.portal.repo;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.siva.portal.configuration.LookupConfig;
import com.siva.portal.database.AbstractMongoDataSource;
import org.bson.Document;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MongoLookupValueDao implements LookupValueDao {

  private final MongoCollection<Document> col;

  public MongoLookupValueDao(AbstractMongoDataSource ds) {
    this.col = ds.getCollection(LookupConfig.COLLECTION);
    ensureIndexes();
  }

  @Override
  public void ensureIndexes() {
    // Unique on "norm" to prevent duplicates (“Spring Boot” vs “spring boot”)
    col.createIndex(Indexes.ascending("norm"), new IndexOptions().unique(true));
    // Optional: unique on "value" if you want strict uniqueness by display string too
    // col.createIndex(Indexes.ascending("value"), new IndexOptions().unique(true));
  }

  @Override
  public boolean existsByNorm(String norm) {
    return col.find(Filters.eq("norm", norm)).limit(1).first() != null;
  }

  @Override
  public void save(String value, String norm) throws Exception {
    var now = Instant.now();
    var doc = new Document()
        .append("value", value)
        .append("norm", norm)
        .append("frequency", 1)
        .append("createdAt", now);
    try {
      // upsert by norm; if you prefer strict "insert only", replace with insertOne(doc)
      col.replaceOne(Filters.eq("norm", norm), doc, new ReplaceOptions().upsert(true));
    } catch (MongoWriteException mwe) {
      // 11000 = duplicate key; for our flow, treat as success/no-op
      if (mwe.getError() != null && mwe.getError().getCode() == 11000) return;
      throw mwe; // rethrow others so WriteBehindStore can keep it queued
    }
  }

  @Override
  public List<String> findAllValues() {
    var out = new ArrayList<String>();
    for (var doc : col.find()) {
      var v = doc.getString("value");
      if (v != null) out.add(v);
    }
    return out;
  }
}
