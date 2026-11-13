package com.surshongi.api.community;

import com.surshongi.api.session.SessionService;
import com.surshongi.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommunityController {
  private final CommunityActions actions;
  private final SessionService sessions;

  public CommunityController(CommunityActions actions, SessionService sessions) {
    this.actions = actions;
    this.sessions = sessions;
  }

  @GetMapping({"/community-data", "/api/community"})
  public Map<String, Object> get(HttpServletRequest request) {
    return actions.databaseFor(sessions.userId(request));
  }

  @PostMapping({"/community-data", "/api/community"})
  public Map<String, Object> post(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    String me = sessions.userId(request);
    if (me == null) throw new ApiException(401, "Please sign in with Google first.");
    return actions.handle(me, body);
  }
}
