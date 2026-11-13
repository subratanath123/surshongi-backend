package com.surshongi.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final String frontendOrigin;

  public WebConfig(@Value("${surshongi.frontend-origin:http://localhost:3000}") String frontendOrigin) {
    this.frontendOrigin = frontendOrigin;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String[] origins = frontendOrigin.split(",");
    for (int i = 0; i < origins.length; i++) {
      origins[i] = origins[i].trim();
    }
    registry
        .addMapping("/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }
}
