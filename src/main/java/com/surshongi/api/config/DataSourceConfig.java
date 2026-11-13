package com.surshongi.api.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {
  @Bean
  DataSource dataSource(@Value("${surshongi.database-url}") String databaseUrl) {
    HikariDataSource ds = new HikariDataSource();
    if (databaseUrl.startsWith("jdbc:")) {
      ds.setJdbcUrl(databaseUrl);
      return ds;
    }
    URI uri = URI.create(databaseUrl.replace("postgresql://", "http://").replace("postgres://", "http://"));
    String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
    int colon = userInfo.indexOf(':');
    String user = colon >= 0 ? decode(userInfo.substring(0, colon)) : decode(userInfo);
    String password = colon >= 0 ? decode(userInfo.substring(colon + 1)) : "";
    int port = uri.getPort() > 0 ? uri.getPort() : 5432;
    String path = uri.getPath() == null ? "/surshongi_db" : uri.getPath();
    ds.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + path);
    ds.setUsername(user);
    ds.setPassword(password);
    ds.setMaximumPoolSize(10);
    return ds;
  }

  private static String decode(String value) {
    return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
