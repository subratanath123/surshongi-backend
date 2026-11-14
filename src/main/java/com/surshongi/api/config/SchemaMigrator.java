package com.surshongi.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigrator implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
  private final MongoTemplate mongo;

  public SchemaMigrator(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public void run(ApplicationArguments args) {
    mongo.indexOps("user_follows").ensureIndex(new Index().on("followerId", Sort.Direction.ASC).on("musicianId", Sort.Direction.ASC).unique());
    mongo.indexOps("user_ratings").ensureIndex(new Index().on("raterId", Sort.Direction.ASC).on("musicianId", Sort.Direction.ASC).unique());
    mongo.indexOps("order_reviews").ensureIndex(new Index().on("orderId", Sort.Direction.ASC).unique());
    mongo.indexOps("chat_messages").ensureIndex(new Index().on("createdAt", Sort.Direction.ASC));
    mongo.indexOps("chat_messages").ensureIndex(new Index().on("toId", Sort.Direction.ASC).on("fromId", Sort.Direction.ASC));
    mongo.indexOps("push_tokens").ensureIndex(new Index().on("token", Sort.Direction.ASC).unique());
    mongo.indexOps("push_tokens").ensureIndex(new Index().on("userId", Sort.Direction.ASC));
    mongo.indexOps("user_activity").ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
    mongo.indexOps("community_posts").ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
    mongo.indexOps("service_orders").ensureIndex(new Index().on("buyerId", Sort.Direction.ASC));
    mongo.indexOps("service_orders").ensureIndex(new Index().on("musicianId", Sort.Direction.ASC));
    log.info("SurShongi MongoDB indexes are ready");
  }
}
