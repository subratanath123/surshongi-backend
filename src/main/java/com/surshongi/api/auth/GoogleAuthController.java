package com.surshongi.api.auth;

import com.surshongi.api.activity.ActivityService;
import com.surshongi.api.community.CommunityActions;
import com.surshongi.api.session.SessionService;
import com.surshongi.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class GoogleAuthController {
  private static final Logger log = LoggerFactory.getLogger(GoogleAuthController.class);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  private final SessionService sessions;
  private final GoogleUsers users;
  private final CommunityActions community;
  private final ActivityService activity;
  private final JsonMapper json;
  private final String siteUrl;
  private final String clientId;
  private final String clientSecret;

  public GoogleAuthController(
      SessionService sessions,
      GoogleUsers users,
      CommunityActions community,
      ActivityService activity,
      JsonMapper json,
      @Value("${surshongi.site-url:http://localhost:3000}") String siteUrl,
      @Value("${surshongi.google-client-id:}") String clientId,
      @Value("${surshongi.google-client-secret:}") String clientSecret) {
    this.sessions = sessions;
    this.users = users;
    this.community = community;
    this.activity = activity;
    this.json = json;
    this.siteUrl = siteUrl.replaceAll("/$", "");
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
  }

  @GetMapping({"/api/auth/google", "/auth/google"})
  public ResponseEntity<Void> start(HttpServletRequest request) {
    String locale = "en".equals(request.getParameter("locale")) ? "en" : "bn";
    String register = "en".equals(locale) ? "/en/register" : "/register";
    String host = forwardedHost(request);
    String siteHost = URI.create(siteUrl).getHost() + portSuffix(URI.create(siteUrl));
    if (host != null && !host.equalsIgnoreCase(siteHost)) {
      return ResponseEntity.status(302).location(URI.create(siteUrl + request.getRequestURI()
          + (request.getQueryString() == null ? "" : "?" + request.getQueryString()))).build();
    }
    if (clientId.isEmpty() || clientSecret.isEmpty()) {
      return ResponseEntity.status(302).location(URI.create(siteUrl + register + "?sso_error=missing_credentials")).build();
    }
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    String state = HexFormat.of().formatHex(nonce) + ":" + locale;
    String redirectUri = siteUrl + "/api/google-oauth/callback-chatbot";
    String google = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(clientId)
        + "&redirect_uri=" + enc(redirectUri)
        + "&response_type=code&scope=" + enc("openid email profile")
        + "&prompt=select_account&state=" + enc(state);
    return ResponseEntity.status(302)
        .header(HttpHeaders.SET_COOKIE, sessions.oauthCookie(state))
        .location(URI.create(google))
        .build();
  }

  @GetMapping({
      "/api/google-oauth/callback-chatbot",
      "/api/google-oauth/callback",
      "/api/auth/google/callback",
      "/api/auth/callback/google",
      "/auth/google/callback"
  })
  public ResponseEntity<Void> callback(HttpServletRequest request) {
    String state = request.getParameter("state") == null ? "" : request.getParameter("state");
    String locale = state.endsWith(":en") ? "en" : "bn";
    String register = "en".equals(locale) ? "/en/register" : "/register";
    String error = request.getParameter("error");
    String code = request.getParameter("code");
    String saved = sessions.readCookie(request, SessionService.OAUTH_COOKIE);
    if (error != null) return fail(register, "access_denied".equals(error) ? "access_denied" : "google_error");
    if (code == null || code.isBlank()) return fail(register, "missing_code");
    if (saved == null || !saved.equals(state)) return fail(register, "state_mismatch");
    if (clientId.isEmpty() || clientSecret.isEmpty()) return fail(register, "missing_credentials");
    try {
      String redirectUri = siteUrl + "/api/google-oauth/callback-chatbot";
      String form = "code=" + enc(code) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
          + "&redirect_uri=" + enc(redirectUri) + "&grant_type=authorization_code";
      Map<String, Object> token = readJson(post("https://oauth2.googleapis.com/token", form));
      Object access = token.get("access_token");
      if (access == null) return fail(register, "token_exchange_failed");
      Map<String, Object> profile = readJson(get("https://openidconnect.googleapis.com/v1/userinfo", String.valueOf(access)));
      String email = profile.get("email") == null ? "" : String.valueOf(profile.get("email")).trim().toLowerCase();
      if (email.isEmpty() || !truthy(profile.get("email_verified"))) return fail(register, "unverified_email");
      if (!GoogleUsers.gmail(email)) return fail(register, "gmail_only");
      Map<String, Object> user = users.findOrCreate(
          profile.get("sub") == null ? null : String.valueOf(profile.get("sub")),
          email,
          profile.get("name") == null ? null : String.valueOf(profile.get("name")),
          profile.get("picture") == null ? null : String.valueOf(profile.get("picture")));
      activity.record("login", String.valueOf(user.get("id")), text(user.get("name")), email, "/");
      boolean completed = Boolean.TRUE.equals(user.get("profileCompleted"));
      String destination = completed
          ? ("en".equals(locale) ? "/en/u/" : "/u/") + user.get("id")
          : register + "?sso_success=1";
      return ResponseEntity.status(302)
          .header(HttpHeaders.SET_COOKIE, sessions.clearOauthCookie())
          .header(HttpHeaders.SET_COOKIE, sessions.sessionCookie(String.valueOf(user.get("id"))))
          .location(URI.create(siteUrl + destination))
          .build();
    } catch (Exception ex) {
      log.error("Google sign-in failed", ex);
      return fail(register, "server_error");
    }
  }

  @PostMapping({"/api/auth/google/token", "/auth/google/token"})
  public Map<String, Object> token(@RequestBody Map<String, Object> body, HttpServletResponse response) throws Exception {
    String credential = body.get("credential") == null ? "" : String.valueOf(body.get("credential"));
    String accessToken = body.get("accessToken") == null ? "" : String.valueOf(body.get("accessToken"));
    if (credential.isBlank() && accessToken.isBlank()) throw new ApiException(400, "Missing Google token");
    if (clientId.isEmpty()) throw new ApiException(500, "Google sign-in is not configured");
    Map<String, Object> info;
    if (!credential.isBlank()) {
      info = readJson(get("https://oauth2.googleapis.com/tokeninfo?id_token=" + enc(credential), null));
      if (!clientId.equals(String.valueOf(info.get("aud")))) throw new ApiException(401, "Client ID mismatch");
    } else {
      Map<String, Object> tokenInfo = readJson(get("https://oauth2.googleapis.com/tokeninfo?access_token=" + enc(accessToken), null));
      if (!clientId.equals(String.valueOf(tokenInfo.get("aud"))) && !clientId.equals(String.valueOf(tokenInfo.get("azp")))) {
        throw new ApiException(401, "Invalid Google access token");
      }
      info = readJson(get("https://openidconnect.googleapis.com/v1/userinfo", accessToken));
    }
    String email = info.get("email") == null ? "" : String.valueOf(info.get("email")).trim().toLowerCase();
    if (email.isEmpty() || !truthy(info.get("email_verified"))) throw new ApiException(400, "Unverified Google email");
    if (!GoogleUsers.gmail(email)) throw new ApiException(400, "Only @gmail.com accounts are allowed.");
    Map<String, Object> user = users.findOrCreate(
        info.get("sub") == null ? null : String.valueOf(info.get("sub")),
        email,
        info.get("name") == null ? null : String.valueOf(info.get("name")),
        info.get("picture") == null ? null : String.valueOf(info.get("picture")));
    response.addHeader(HttpHeaders.SET_COOKIE, sessions.sessionCookie(String.valueOf(user.get("id"))));
    activity.record("login", String.valueOf(user.get("id")), text(user.get("name")), email, "/");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("db", community.databaseFor(String.valueOf(user.get("id"))));
    payload.put("user", user);
    return payload;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private ResponseEntity<Void> fail(String register, String reason) {
    log.warn("Google sign-in {}", reason);
    return ResponseEntity.status(302)
        .header(HttpHeaders.SET_COOKIE, sessions.clearOauthCookie())
        .location(URI.create(siteUrl + register + "?sso_error=" + reason))
        .build();
  }

  private String post(String url, String form) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(12))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) throw new IllegalStateException("Google token exchange failed");
    return response.body();
  }

  private String get(String url, String bearer) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET();
    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
    HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) throw new ApiException(401, "Invalid Google token");
    return response.body();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJson(String body) {
    return json.readValue(body, Map.class);
  }

  private static boolean truthy(Object value) {
    return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
  }

  private static String forwardedHost(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-Host");
    String host = forwarded == null || forwarded.isBlank() ? request.getHeader("Host") : forwarded.split(",")[0].trim();
    return host;
  }

  private static String portSuffix(URI uri) {
    if (uri.getPort() < 0) return "";
    boolean standard = ("https".equals(uri.getScheme()) && uri.getPort() == 443) || ("http".equals(uri.getScheme()) && uri.getPort() == 80);
    return standard ? "" : ":" + uri.getPort();
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
