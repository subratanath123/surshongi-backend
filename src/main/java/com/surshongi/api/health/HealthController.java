package com.surshongi.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  @GetMapping({"/api/health", "/health", "/actuator/health"})
  public Map<String, Object> health() {
    return Map.of("ok", true, "service", "surshongi-api");
  }
}
