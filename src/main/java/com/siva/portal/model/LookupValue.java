// model/LookupValue.java
package com.siva.portal.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document("lookup_values")
public class LookupValue {

  @Id
  private String id;

  private String value;

  /**
   * Normalized value (lower/trim). Make ONLY this unique to avoid conflicts between case variants.
   * Example: value="Spring Boot", norm="spring boot"
   */
  @Indexed(unique = true)   // <-- keep ONLY this one unique
  private String norm;

  @Builder.Default
  private int frequency = 1;

  /** Prefer BSON Date in Mongo for easy parsing; use java.util.Date here. */
  @Builder.Default
  private Date createdAt = new Date();
}
