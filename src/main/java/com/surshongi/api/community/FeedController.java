package com.surshongi.api.community;

import com.surshongi.api.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedController {
  private final FeedService feed;
  private final SessionService sessions;

  public FeedController(FeedService feed, SessionService sessions) {
    this.feed = feed;
    this.sessions = sessions;
  }

  @GetMapping({"/community-feed", "/api/feed"})
  public Map<String, Object> page(
      HttpServletRequest request,
      @RequestParam(name = "filter", required = false) String filter,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false, defaultValue = "8") int limit) {
    return feed.page(sessions.userId(request), filter, cursor, limit);
  }
}
