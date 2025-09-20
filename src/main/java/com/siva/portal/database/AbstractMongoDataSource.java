package com.siva.portal.database;

import com.mongodb.client.*;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.ArrayList;
import java.util.List;

@Configuration
public abstract class AbstractMongoDataSource {
    private static final String DEFAULT_MONGO_URI = "mongodb://localhost:27017/";
    private static final String DEFAULT_DATABASE_NAME = "portal"; // Replace with your DB name

    protected MongoClient mongoClient;
    protected MongoDatabase database;

    public AbstractMongoDataSource() {
        String uri = getenvOrDefault("MONGODB_URI", DEFAULT_MONGO_URI);
        String db  = getenvOrDefault("MONGODB_DB", DEFAULT_DATABASE_NAME);
        mongoClient = MongoClients.create(uri);
        database = mongoClient.getDatabase(db);
    }

    public MongoCollection<Document> getCollection(String collectionName) {
        if(this.database == null || !isConnectionAlive()) mongoDatabaseFactory();
        return database.getCollection(collectionName);
    }

    private boolean isConnectionAlive() {
        try {
            if(this.database == null) return false;
            Document ping = new Document("ping", 1);
            this.database.runCommand(ping);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void mongoDatabaseFactory() {
        try {
            if(this.database != null) return;
            if(this.mongoClient == null) {
                String uri = getenvOrDefault("MONGODB_URI", DEFAULT_MONGO_URI);
                this.mongoClient = MongoClients.create(uri);
            }
            String db = getenvOrDefault("MONGODB_DB", DEFAULT_DATABASE_NAME);
            this.database = new SimpleMongoClientDatabaseFactory(this.mongoClient, db).getMongoDatabase();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getenvOrDefault(String key, String def) {
        try {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : v;
        } catch (SecurityException se) {
            return def;
        }
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
