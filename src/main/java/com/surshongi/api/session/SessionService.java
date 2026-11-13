package com.surshongi.api.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  public static final String COOKIE = "sur_session_user";
  public static final String OAUTH_COOKIE = "sur_oauth_state";
  private static final long MAX_AGE_SECONDS = 60L * 60 * 24 * 30;

  private final String secret;
  private final boolean secure;

  public SessionService(
      @Value("${surshongi.session-secret:}") String secret,
      @Value("${surshongi.site-url:http://localhost:3000}") String siteUrl) {
    this.secret = secret == null ? "" : secret.trim();
    this.secure = siteUrl.startsWith("https://");
  }

  public String create(String userId) {
    if (secret.isEmpty()) {
      throw new IllegalStateException("SESSION_SECRET (or GOOGLE_CLIENT_SECRET) must be set to sign sessions.");
    }
    long expires = (System.currentTimeMillis() / 1000L) + MAX_AGE_SECONDS;
    String payload = b64(userId.getBytes(StandardCharsets.UTF_8)) + "." + expires;
    return payload + "." + sign(payload);
  }

  public String verify(String token) {
    if (token == null || token.isBlank() || secret.isEmpty()) return null;
    String[] parts = token.split("\\.");
    if (parts.length != 3) return null;
    String payload = parts[0] + "." + parts[1];
    byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
    byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
    if (actual.length != expected.length || !MessageDigest.isEqual(actual, expected)) return null;
    long expires;
    try {
      expires = Long.parseLong(parts[1]);
    } catch (NumberFormatException ex) {
      return null;
    }
    if (expires * 1000L < System.currentTimeMillis()) return null;
    byte[] id = Base64.getUrlDecoder().decode(pad(parts[0]));
    String userId = new String(id, StandardCharsets.UTF_8);
    return userId.isEmpty() ? null : userId;
  }

  public String readCookie(jakarta.servlet.http.HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    for (var cookie : request.getCookies()) {
      if (name.equals(cookie.getName())) return cookie.getValue();
    }
    return null;
  }

  public String userId(jakarta.servlet.http.HttpServletRequest request) {
    return verify(readCookie(request, COOKIE));
  }

  public String sessionCookie(String userId) {
    return ResponseCookie.from(COOKIE, create(userId))
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .secure(secure)
        .maxAge(Duration.ofSeconds(MAX_AGE_SECONDS))
        .build()
        .toString();
  }

  public String clearSessionCookie() {
    return ResponseCookie.from(COOKIE, "")
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .secure(secure)
        .maxAge(0)
        .build()
        .toString();
  }

  public String oauthCookie(String state) {
    return ResponseCookie.from(OAUTH_COOKIE, state)
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .secure(secure)
        .maxAge(Duration.ofSeconds(600))
        .build()
        .toString();
  }

  public String clearOauthCookie() {
    return ResponseCookie.from(OAUTH_COOKIE, "")
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .secure(secure)
        .maxAge(0)
        .build()
        .toString();
  }

  public void addCookie(HttpHeaders headers, String cookie) {
    headers.add(HttpHeaders.SET_COOKIE, cookie);
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return b64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not sign session", ex);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String pad(String value) {
    int mod = value.length() % 4;
    return mod == 0 ? value : value + "=".repeat(4 - mod);
  }
}
