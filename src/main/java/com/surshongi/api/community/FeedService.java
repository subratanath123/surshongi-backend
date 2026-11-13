package com.surshongi.api.community;

import com.surshongi.api.community.CommunityRepository.FeedCursor;
import com.surshongi.api.community.CommunityRepository.FeedSlot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class FeedService {
  private static final Set<String> FILTERS = Set.of("all", "following", "vocal", "guitar", "percussion", "folk");
  private final CommunityRepository repo;

  public FeedService(CommunityRepository repo) {
    this.repo = repo;
  }

  public Map<String, Object> page(String viewerId, String filter, String cursorText, int limit) {
    String safeFilter = filter != null && FILTERS.contains(filter) ? filter : "all";
    int size = Math.min(20, Math.max(1, limit <= 0 ? 8 : limit));
    List<String> preferences = repo.feedPreferences(viewerId);
    List<FeedSlot> slots = repo.pageFeed(
        viewerId,
        safeFilter,
        preferences.toArray(String[]::new),
        decode(cursorText),
        size + 1);
    boolean more = slots.size() > size;
    if (more) slots = new ArrayList<>(slots.subList(0, size));
    List<String> postIds = new ArrayList<>();
    for (FeedSlot slot : slots) postIds.add(slot.id());
    Map<String, Map<String, Object>> posts = index(repo.postsByIds(postIds));
    List<String> authorIds = new ArrayList<>();
    for (Map<String, Object> post : posts.values()) authorIds.add(String.valueOf(post.get("musicianId")));
    Map<String, Map<String, Object>> authors = index(repo.usersByIds(authorIds));

    List<Map<String, Object>> cards = new ArrayList<>();
    for (FeedSlot slot : slots) {
      Map<String, Object> post = posts.get(slot.id());
      if (post == null) continue;
      Map<String, Object> author = authors.get(String.valueOf(post.get("musicianId")));
      if (author == null) continue;
      Map<String, Object> safeAuthor = new LinkedHashMap<>(author);
      safeAuthor.remove("email");
      safeAuthor.remove("googleSub");
      safeAuthor.remove("phone");
      safeAuthor.remove("bkashNumber");
      safeAuthor.remove("feedPreferences");
      Map<String, Object> card = new LinkedHashMap<>();
      card.put("post", post);
      card.put("author", safeAuthor);
      card.put("preferred", slot.preferred() == 1);
      cards.add(card);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("posts", cards);
    body.put("preferences", preferences);
    if (more && !slots.isEmpty()) body.put("next", encode(slots.get(slots.size() - 1)));
    return body;
  }

  private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
    Map<String, Map<String, Object>> map = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) map.put(String.valueOf(row.get("id")), row);
    return map;
  }

  private static String encode(FeedSlot slot) {
    String raw = slot.preferred() + "\n" + slot.ratingCount() + "\n" + slot.popular() + "\n"
        + (slot.rating() == null ? "0" : slot.rating().toPlainString()) + "\n"
        + slot.createdAtEpoch() + "\n" + slot.id();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static FeedCursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("\n", 6);
      if (parts.length != 6) return null;
      return new FeedCursor(
          Integer.parseInt(parts[0]),
          Integer.parseInt(parts[1]),
          Integer.parseInt(parts[2]),
          new BigDecimal(parts[3]),
          Timestamp.from(Instant.ofEpochMilli(Long.parseLong(parts[4]))),
          parts[5]);
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
