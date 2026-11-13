package com.surshongi.api.search;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
  private final SearchService search;

  public SearchController(SearchService search) {
    this.search = search;
  }

  @GetMapping({"/community-search", "/api/search"})
  public Map<String, Object> get(@RequestParam(name = "q", required = false) String q) {
    return search.search(q);
  }

  @PostMapping({"/community-search", "/api/search"})
  public Map<String, Object> post(@RequestBody(required = false) Map<String, Object> body) {
    Object query = body == null ? null : body.get("query");
    return search.search(query == null ? "" : String.valueOf(query));
  }
}
