package com.siva.portal.database;

import com.mongodb.client.*;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public abstract class AbstractMongoDataSource {
    private static final String MONGO_URI = "mongodb://localhost:27017/";
    private static final String DATABASE_NAME = "portal"; // Replace with your DB name

    protected MongoClient mongoClient;
    protected MongoDatabase database;

    public AbstractMongoDataSource() {
        mongoClient = MongoClients.create(MONGO_URI);
        database = mongoClient.getDatabase(DATABASE_NAME);
    }

    public MongoCollection<Document> getCollection(String collectionName) {
        return database.getCollection(collectionName);
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}