package com.surshongi.api.community;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicUserController {
  private final CommunityRepository repo;

  public PublicUserController(CommunityRepository repo) {
    this.repo = repo;
  }

  @GetMapping("/api/users/{id}/public")
  public Map<String, Object> name(@PathVariable String id) {
    String name = repo.publicName(id);
    return name == null ? Map.of() : Map.of("name", name);
  }
}
