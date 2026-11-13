package com.surshongi.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigrator implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
  private final JdbcTemplate jdbc;

  public SchemaMigrator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(ApplicationArguments args) {
    String[] statements = {
      "ALTER TABLE users ADD COLUMN IF NOT EXISTS gender TEXT",
      "ALTER TABLE users ADD COLUMN IF NOT EXISTS selfie_verified BOOLEAN NOT NULL DEFAULT FALSE",
      "ALTER TABLE users ADD COLUMN IF NOT EXISTS feed_preferences JSONB NOT NULL DEFAULT '[]'::jsonb",
      "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS post_id TEXT",
      "ALTER TABLE service_orders ADD COLUMN IF NOT EXISTS payment_status TEXT NOT NULL DEFAULT 'none'",
      "ALTER TABLE service_orders ADD COLUMN IF NOT EXISTS payment_submitted_at TIMESTAMPTZ",
      "ALTER TABLE service_orders ADD COLUMN IF NOT EXISTS payment_confirmed_at TIMESTAMPTZ",
      "ALTER TABLE service_orders ADD COLUMN IF NOT EXISTS payment_dispute_note TEXT",
    };
    for (String sql : statements) {
      jdbc.execute(sql);
    }
    log.info("SurShongi schema is ready");
  }
}
