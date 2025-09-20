// repo/MongoLookupValueDao.java
package com.siva.portal.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.siva.portal.configuration.LookupConfig;
import com.siva.portal.database.AbstractMongoDataSource;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class MongoLookupValueDao implements LookupValueDao {

  private static final Logger LOG = LoggerFactory.getLogger(MongoLookupValueDao.class);
  private final MongoCollection<Document> col;

  public MongoLookupValueDao(AbstractMongoDataSource ds) {
    this.col = ds.getCollection(LookupConfig.COLLECTION);
    ensureIndexes();
  }

  @Override
  public void ensureIndexes() {
    // 1) Drop legacy unique index on 'norm' (from older per-value schema) if present,
    //    since bucket documents don't have 'norm' at top level and a unique index on
    //    a missing field would allow only one such document.
    try {
      for (Document ix : col.listIndexes()) {
        boolean unique = Boolean.TRUE.equals(ix.get("unique"));
        @SuppressWarnings("unchecked")
        Document keyDoc = (Document) ix.get("key");
        if (unique && keyDoc != null) {
          boolean hasNorm = keyDoc.containsKey("norm");
          boolean hasKey  = keyDoc.containsKey("key");
          if (hasNorm && !hasKey) {
            String name = ix.getString("name");
            if (name != null && !name.isBlank()) {
              col.dropIndex(name);
              LOG.info("Dropped legacy unique index '{}' on 'norm'", name);
            }
          }
        }
      }
    } catch (RuntimeException e) {
      // Best-effort; indexing issues shouldn't block app startup
      LOG.warn("Failed to evaluate/drop legacy 'norm' index; continuing", e);
    }

    // 2) Ensure a unique index on top-level 'key' only for bucket documents.
    var filter = new Document("$and", List.of(
            new Document("key", new Document("$exists", true)),
            new Document("key", new Document("$type", "string"))
    ));

    var opts = new IndexOptions().unique(true).partialFilterExpression(filter);
    col.createIndex(Indexes.ascending("key"), opts);
  }

  @Override
  public List<String> findAllValues(String key) {
    var doc = col.find(Filters.eq("key", key)).limit(1).first();
    if (doc != null) {
      var arr = (List<Document>) doc.getOrDefault("values", List.of());
      return arr.stream().map(d -> d.getString("value")).filter(Objects::nonNull).toList();
    }

    // Fallback for legacy: collect distinct 'value' from docs without a 'key' field
    var cursor = col.find(new Document("key", new Document("$exists", false)));
    List<String> out = new ArrayList<>();
    for (var d : cursor) {
      var v = d.getString("value");
      if (v != null) out.add(v);
    }
    return out;
  }

  @Override
  public Optional<Bucket> getBucket(String key) {
    var d = col.find(Filters.eq("key", key)).limit(1).first();
    if (d == null) return Optional.empty();

    @SuppressWarnings("unchecked")
    var arr = (List<Document>) d.getOrDefault("values", List.of());

    var values = arr.stream().map(x -> new DocValue(
            x.getString("value"),
            x.getString("norm"),
            x.getInteger("frequency", 1),
            safeToEpochMillis(x.get("createdAt"))
    )).collect(Collectors.toList());

    return Optional.of(new Bucket(d.getString("key"), values));
  }

  @Override
  public void upsertBucket(String key, List<DocValue> values) throws Exception {
    var valuesDocs = values.stream().map(v -> new Document()
            .append("value", v.value())
            .append("norm", v.norm())
            .append("frequency", v.frequency())
            .append("createdAt", Instant.ofEpochMilli(v.createdAt()))
    ).collect(Collectors.toList());

    var doc = new Document()
            .append("key", key)
            .append("values", valuesDocs)
            .append("updatedAt", Instant.now());

    col.replaceOne(Filters.eq("key", key), doc, new ReplaceOptions().upsert(true));
  }

  // Add these members in MongoLookupValueDao:
  private static final DateTimeFormatter RFC_1123 =
          DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);
  private static final DateTimeFormatter JAVA_UTIL_DATE_STR =
          DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);

  // Defensive conversion to epoch millis from various stored types.
  private long safeToEpochMillis(Object raw) {
    try {
      if (raw == null) return System.currentTimeMillis();

      // Already a BSON Date?
      if (raw instanceof java.util.Date dt) return dt.getTime();

      // Already an Instant?
      if (raw instanceof Instant ins) return ins.toEpochMilli();

      // Numeric epoch?
      if (raw instanceof Number n) return n.longValue();

      if (raw instanceof String s) {
        String t = s.trim();
        if (t.isEmpty()) return System.currentTimeMillis();

        // 1) Numeric epoch string
        try { return Long.parseLong(t); } catch (Exception ignored) {}

        // 2) ISO-8601
        try { return Instant.parse(t).toEpochMilli(); } catch (Exception ignored) {}

        // 3) RFC-1123 (e.g., "Sun, 14 Sep 2025 18:37:42 GMT")
        try { return ZonedDateTime.parse(t, RFC_1123).toInstant().toEpochMilli(); } catch (Exception ignored) {}

        // 4) java.util.Date.toString() format (your case): "Sun Sep 14 18:37:42 IST 2025"
        try { return ZonedDateTime.parse(t, JAVA_UTIL_DATE_STR).toInstant().toEpochMilli(); } catch (Exception ignored) {}

        // Fallback: now
        return System.currentTimeMillis();
      }

      // Unknown type → fallback
      return System.currentTimeMillis();
    } catch (Exception e) {
      return System.currentTimeMillis();
    }
  }
}
