package com.surshongi.api.auth;

import com.surshongi.api.community.CommunityRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GoogleUsers {
  private final CommunityRepository repo;

  public GoogleUsers(CommunityRepository repo) {
    this.repo = repo;
  }

  public static boolean gmail(String email) {
    return email.endsWith("@gmail.com") || email.endsWith("@googlemail.com");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> findOrCreate(String sub, String email, String name, String picture) {
    String normalized = email.trim().toLowerCase();
    Map<String, Object> db = repo.read();
    List<Map<String, Object>> users = (List<Map<String, Object>>) db.get("users");
    Map<String, Object> existing = null;
    for (Map<String, Object> user : users) {
      if ((sub != null && sub.equals(user.get("googleSub"))) || normalized.equals(String.valueOf(user.get("email")).toLowerCase())) {
        existing = user;
        break;
      }
    }
    if (existing != null) {
      String avatar = existing.get("avatarUrl") == null ? "" : String.valueOf(existing.get("avatarUrl"));
      if (picture != null && !picture.equals(avatar) && !avatar.startsWith("/uploads/")) {
        Map<String, Object> updated = new LinkedHashMap<>(existing);
        if (sub != null) updated.put("googleSub", sub);
        updated.put("avatarUrl", picture);
        repo.upsertUser(updated);
        return updated;
      }
      return existing;
    }
    String local = normalized.split("@")[0].replaceAll("[^a-z0-9]", "");
    if (local.length() > 12) local = local.substring(0, 12);
    String tail = Long.toString(System.currentTimeMillis(), 36);
    tail = tail.substring(Math.max(0, tail.length() - 4));
    String id = "u-" + (local.isEmpty() ? "member" : local) + "-" + tail;
    String display = name == null || name.isBlank() ? normalized.split("@")[0] : name.trim();
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", id);
    user.put("email", normalized);
    user.put("googleSub", sub == null || sub.isBlank() ? "google-" + id : sub);
    if (picture != null && !picture.isBlank()) user.put("avatarUrl", picture);
    user.put("profileCompleted", false);
    user.put("name", display);
    user.put("nameBn", display);
    user.put("city", "Dhaka");
    user.put("cityBn", "ঢাকা");
    user.put("cityTerms", List.of("dhaka", "ঢাকা"));
    user.put("roles", List.of("vocalist"));
    user.put("instruments", List.of("Voice"));
    user.put("instrumentsBn", List.of("কণ্ঠ"));
    user.put("genres", List.of("Folk"));
    user.put("genresBn", List.of("লোক"));
    user.put("genreTerms", List.of("folk", "লোক"));
    user.put("languages", List.of("Bangla", "English"));
    user.put("languagesBn", List.of("বাংলা", "ইংরেজি"));
    user.put("languageTerms", List.of("bangla", "বাংলা", "english", "ইংরেজি"));
    user.put("bio", "");
    user.put("bioBn", "");
    user.put("openToWork", true);
    user.put("mood", List.of());
    user.put("moodBn", List.of());
    user.put("moodTerms", List.of());
    user.put("followers", 0);
    user.put("rating", 5.0);
    user.put("ratingCount", 0);
    repo.upsertUser(user);
    return user;
  }
}
