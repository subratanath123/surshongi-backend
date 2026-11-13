package com.surshongi.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SurshongiApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(SurshongiApiApplication.class, args);
  }
}
