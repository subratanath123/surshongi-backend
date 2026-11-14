package com.surshongi.api.activity;

import com.surshongi.api.community.CommunityRepository;
import com.surshongi.api.session.SessionService;
import com.surshongi.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {
  private final ActivityService activity;
  private final CommunityRepository repo;
  private final SessionService sessions;

  public ActivityController(ActivityService activity, CommunityRepository repo, SessionService sessions) {
    this.activity = activity;
    this.repo = repo;
    this.sessions = sessions;
  }

  @PostMapping({"/community-activity", "/api/community-activity"})
  public Map<String, Object> record(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    Object raw = body.get("path");
    String path = raw == null ? "" : String.valueOf(raw);
    String userId = sessions.userId(request);
    String name = "";
    String email = "";
    if (userId != null) {
      Map<String, Object> user = repo.userById(userId);
      if (user != null) {
        name = text(user.get("name"));
        email = text(user.get("email"));
      }
    }
    activity.record("page", userId, name, email, path);
    return Map.of("ok", true);
  }

  @GetMapping({"/community-activity", "/api/community-activity"})
  public Map<String, Object> list(HttpServletRequest request) {
    String userId = sessions.userId(request);
    Map<String, Object> user = userId == null ? null : repo.userById(userId);
    String email = user == null ? "" : text(user.get("email"));
    if (!activity.isAdmin(email)) throw new ApiException(403, "This view is only for the admin account.");
    return Map.of("events", activity.recent());
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
