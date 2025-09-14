package com.siva.portal.database;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

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
                this.mongoClient = MongoClients.create();
            }
            this.database = new SimpleMongoClientDatabaseFactory(this.mongoClient, DATABASE_NAME).getMongoDatabase();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}