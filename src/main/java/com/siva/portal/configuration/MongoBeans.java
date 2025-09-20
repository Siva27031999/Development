// config/MongoBeans.java
package com.siva.portal.configuration;
import com.siva.portal.database.AbstractMongoDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MongoBeans {

  @Bean
  public com.siva.portal.repo.LookupValueDao lookupValueDao(AbstractMongoDataSource ds) {
    return new com.siva.portal.repo.MongoLookupValueDao(ds);
  }
}
