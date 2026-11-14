package com.surshongi.api.push;

import com.surshongi.api.community.CommunityRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PushService {
  private static final Logger log = LoggerFactory.getLogger(PushService.class);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

  private final CommunityRepository repo;
  private final JsonMapper json;
  private final String credentialsPath;
  private final String siteUrl;
  private volatile Credentials credentials;
  private volatile String accessToken;
  private volatile Instant accessTokenExpires = Instant.EPOCH;
  private volatile boolean missingLogged;

  public PushService(
      CommunityRepository repo,
      JsonMapper json,
      @Value("${surshongi.firebase-credentials:}") String credentialsPath,
      @Value("${surshongi.site-url:https://surshongi.com}") String siteUrl
  ) {
    this.repo = repo;
    this.json = json;
    this.credentialsPath = credentialsPath == null ? "" : credentialsPath.trim();
    this.siteUrl = siteUrl == null || siteUrl.isBlank() ? "https://surshongi.com" : siteUrl.replaceAll("/$", "");
  }

  public void notifyNewMessage(String userId, String sender, String text) {
    Thread.startVirtualThread(() -> {
      try {
        String title = sender == null || sender.isBlank() ? "SurShongi" : sender;
        String body = text == null ? "" : text.strip();
        if (body.length() > 140) body = body.substring(0, 137) + "...";
        for (String token : repo.pushTokens(userId)) {
          send(token, title, body, siteUrl);
        }
      } catch (Exception err) {
        log.warn("Message notification failed: {}", err.getMessage());
      }
    });
  }

  private void send(String token, String title, String body, String url) throws Exception {
    Credentials creds = credentials();
    if (creds == null) return;
    String payload = "{\"message\":{\"token\":\"" + escape(token) + "\",\"data\":{"
        + "\"title\":\"" + escape(title) + "\","
        + "\"body\":\"" + escape(body) + "\","
        + "\"url\":\"" + escape(url) + "\"}}}";
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "https://fcm.googleapis.com/v1/projects/" + creds.projectId + "/messages:send"))
        .timeout(Duration.ofSeconds(8))
        .header("Authorization", "Bearer " + accessToken(creds))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404 || response.body().contains("UNREGISTERED")) {
      repo.deletePushToken(token);
      return;
    }
    if (response.statusCode() >= 300) {
      log.warn("Firebase rejected a message notification ({})", response.statusCode());
    }
  }

  private String accessToken(Credentials creds) throws Exception {
    if (accessToken != null && accessTokenExpires.isAfter(Instant.now().plusSeconds(60))) return accessToken;
    long now = Instant.now().getEpochSecond();
    String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
    String claims = b64("{\"iss\":\"" + escape(creds.clientEmail)
        + "\",\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\""
        + ",\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
    String unsigned = header + "." + claims;
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(creds.privateKey);
    signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
    String jwt = unsigned + "." + b64(signature.sign());
    String form = "grant_type=" + url("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + url(jwt);
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
        .timeout(Duration.ofSeconds(8))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) throw new IllegalStateException("Firebase sign-in failed");
    @SuppressWarnings("unchecked")
    Map<String, Object> body = json.readValue(response.body(), Map.class);
    Object token = body.get("access_token");
    if (!(token instanceof String value) || value.isBlank()) throw new IllegalStateException("Firebase sign-in failed");
    accessToken = value;
    accessTokenExpires = Instant.now().plusSeconds(3000);
    return value;
  }

  private Credentials credentials() {
    if (credentials != null) return credentials;
    if (credentialsPath.isBlank()) {
      if (!missingLogged) {
        missingLogged = true;
        log.info("Message notifications are off until FIREBASE_CREDENTIALS points at the service-account file");
      }
      return null;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> file = json.readValue(Files.readString(Path.of(credentialsPath)), Map.class);
      String projectId = text(file.get("project_id"));
      String clientEmail = text(file.get("client_email"));
      String pem = text(file.get("private_key"));
      if (projectId.isBlank() || clientEmail.isBlank() || pem.isBlank()) return null;
      String der = pem
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "");
      PrivateKey key = KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(der)));
      credentials = new Credentials(projectId, clientEmail, key);
      return credentials;
    } catch (Exception err) {
      log.warn("Firebase credentials could not be read: {}", err.getMessage());
      return null;
    }
  }

  private static String text(Object value) {
    return value instanceof String s ? s : "";
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "");
  }

  private static String b64(String value) {
    return b64(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String url(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Credentials(String projectId, String clientEmail, PrivateKey privateKey) {}
}
