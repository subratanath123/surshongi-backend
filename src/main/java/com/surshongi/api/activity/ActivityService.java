package com.surshongi.api.activity;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {
  public static final String ADMIN_EMAIL = "shuvra.dev9@gmail.com";
  private static final String COLLECTION = "user_activity";
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final MongoTemplate mongo;

  public ActivityService(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public boolean isAdmin(String email) {
    return email != null && ADMIN_EMAIL.equals(email.trim().toLowerCase());
  }

  public void record(String action, String userId, String name, String email, String path) {
    String cleanPath = path == null ? "" : path.trim();
    if ("page".equals(action)) {
      int query = cleanPath.indexOf('?');
      if (query >= 0) cleanPath = cleanPath.substring(0, query);
      if (!cleanPath.startsWith("/") || cleanPath.startsWith("//") || cleanPath.length() > 180) return;
      if (cleanPath.startsWith("/api") || cleanPath.startsWith("/auth") || cleanPath.startsWith("/community-")) return;
      String owner = userId == null ? "" : userId;
      Query recent = Query.query(Criteria.where("action").is("page")
          .and("userId").is(owner)
          .and("path").is(cleanPath)
          .and("createdAt").gt(Date.from(Instant.now().minusSeconds(20))));
      if (mongo.exists(recent, COLLECTION)) return;
    }
    Document doc = new Document("_id", "act-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
        .append("userId", userId == null ? "" : userId)
        .append("name", name == null ? "" : name)
        .append("email", email == null ? "" : email.trim().toLowerCase())
        .append("action", action)
        .append("path", cleanPath)
        .append("createdAt", new Date());
    mongo.save(doc, COLLECTION);
    long count = mongo.count(new Query(), COLLECTION);
    if (count > 4000) {
      Query oldest = new Query().with(Sort.by(Sort.Direction.ASC, "createdAt")).limit(500);
      for (Document stale : mongo.find(oldest, Document.class, COLLECTION)) {
        mongo.remove(Query.query(Criteria.where("_id").is(stale.get("_id"))), COLLECTION);
      }
    }
  }

  public List<Map<String, Object>> recent() {
    Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200);
    List<Map<String, Object>> events = new ArrayList<>();
    for (Document doc : mongo.find(query, Document.class, COLLECTION)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", String.valueOf(doc.get("_id")));
      row.put("name", text(doc.get("name")));
      row.put("email", text(doc.get("email")));
      row.put("action", text(doc.get("action")));
      row.put("path", text(doc.get("path")));
      Object created = doc.get("createdAt");
      row.put("createdAt", created instanceof Date date ? ISO.format(date.toInstant()) : "");
      events.add(row);
    }
    return events;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
