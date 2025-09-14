// model/LookupValue.java
package com.siva.portal.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document("lookup_values")
public class LookupValue {
  @Id private String id;

  @Indexed(unique = true)
  private String value;        // original (case preserved)

  @Indexed(unique = true)
  private String norm;         // normalized (lower/trim)

  private int frequency;       // optional scoring
  private Instant createdAt = Instant.now();

  public LookupValue() {}
  public LookupValue(String value, String norm) {
    this.value = value; this.norm = norm;
    this.frequency = 1;
  }
  // getters/setters...
}
