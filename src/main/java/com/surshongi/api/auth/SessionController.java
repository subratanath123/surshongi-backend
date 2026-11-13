package com.surshongi.api.auth;

import com.surshongi.api.community.CommunityRepository;
import com.surshongi.api.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
  private final CommunityRepository repo;
  private final SessionService sessions;
  private final String clientId;
  private final String clientSecret;

  public SessionController(
      CommunityRepository repo,
      SessionService sessions,
      @Value("${surshongi.google-client-id:}") String clientId,
      @Value("${surshongi.google-client-secret:}") String clientSecret) {
    this.repo = repo;
    this.sessions = sessions;
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
  }

  @GetMapping({"/auth/session", "/api/auth/session"})
  public Map<String, Object> get(HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ssoConfigured", !clientId.isEmpty() && !clientSecret.isEmpty());
    body.put("googleClientId", clientId);
    String id = sessions.userId(request);
    if (id == null) {
      body.put("user", null);
      return body;
    }
    Map<String, Object> db = repo.read();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> users = (List<Map<String, Object>>) db.get("users");
    Map<String, Object> user = null;
    for (Map<String, Object> row : users) {
      if (id.equals(row.get("id"))) user = row;
    }
    body.put("user", user);
    return body;
  }

  @DeleteMapping({"/auth/session", "/api/auth/session"})
  public Map<String, Object> delete(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, sessions.clearSessionCookie());
    return Map.of("ok", true);
  }
}
