package com.surshongi.api.search;

import com.surshongi.api.community.CommunityRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
  private static final Set<String> STOP = Set.of(
      "a", "an", "the", "in", "at", "on", "of", "for", "to", "and", "or", "with", "from", "by",
      "is", "are", "who", "can", "me", "my", "i", "we", "want", "find", "search", "show", "some",
      "any", "near", "around", "someone", "person", "people", "musician", "musicians", "artist",
      "need", "needs", "looking", "hire", "good", "best", "please", "also", "like", "that", "this",
      "আমি", "আমার", "একজন", "চাই", "দরকার", "খুঁজছি", "এবং", "ও", "এর", "জন্য", "কে", "যে", "শিল্পী");
  private static final Map<String, String> ROLES = Map.of(
      "vocalist", "vocal vocalist singer voice sing song lead melody কণ্ঠ গান গায়ক গায়িকা সুর",
      "guitarist", "guitar guitarist guiter acoustic electric chords solo riff গিটার গিটারিস্ট",
      "percussion", "percussion tabla dhol drum drums rhythm beat তবলা ঢোল ড্রাম",
      "flute", "flute bansuri woodwind bamboo বাঁশি বংশী",
      "producer", "producer mix mixing master mastering arrangement studio daw মিক্স মাস্টার প্রোডিউসার",
      "lyricist", "lyricist lyrics songwriter composer poem words কথা গীতিকার সুরকার");
  private static final Pattern TOKEN = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\s]");
  private static final Pattern BUDGET = Pattern.compile("(\\d{3,6})");

  private final CommunityRepository repo;

  public SearchService(CommunityRepository repo) {
    this.repo = repo;
  }

  public Map<String, Object> search(String raw) {
    String query = raw == null ? "" : raw.trim();
    if (query.length() > 200) query = query.substring(0, 200);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    if (query.isEmpty()) {
      body.put("hits", List.of());
      return body;
    }
    Map<String, Object> db = repo.read();
    List<String> tokens = tokens(query.toLowerCase(Locale.ROOT));
    List<Map<String, Object>> hits = new ArrayList<>();
    for (Map<String, Object> user : users(db)) {
      if (!Boolean.TRUE.equals(user.get("profileCompleted")) || Boolean.TRUE.equals(user.get("isPrivate"))) continue;
      if ("member".equals(user.get("accountKind"))) continue;
      Hit hit = match(query, tokens, user, db);
      if (hit == null || hit.score < 0.2) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      Map<String, Object> copy = new LinkedHashMap<>(user);
      copy.remove("email");
      copy.remove("googleSub");
      row.put("user", copy);
      row.put("similarity", Math.min(99, Math.round(hit.score * 100)));
      row.put("reasons", hit.reasons.isEmpty() ? List.of("semantic") : hit.reasons);
      row.put("matchedText", hit.matchedText);
      row.put("matchedSource", hit.matchedSource);
      row.put("matchedTerms", hit.terms);
      List<Map<String, Object>> cols = collections(db, str(user.get("id")));
      row.put("showcaseCount", cols.size());
      for (Map<String, Object> col : cols) {
        if (col.get("youtubeId") != null) {
          row.put("thumbnailYoutubeId", col.get("youtubeId"));
          break;
        }
      }
      hits.add(row);
    }
    hits.sort(Comparator
        .comparingInt((Map<String, Object> hit) -> -((Number) hit.get("similarity")).intValue())
        .thenComparingDouble(hit -> -number(userOf(hit).get("rating"))));
    body.put("hits", hits);
    return body;
  }

  private Hit match(String query, List<String> tokens, Map<String, Object> user, Map<String, Object> db) {
    List<Field> fields = fields(user, db);
    List<String> reasons = new ArrayList<>();
    List<String> terms = new ArrayList<>();
    int matched = 0;
    for (String token : tokens) {
      boolean group = false;
      for (Field field : fields) {
        if (field.text.toLowerCase(Locale.ROOT).contains(token)) {
          group = true;
          if (!reasons.contains(field.reason)) reasons.add(field.reason);
          if (!terms.contains(token)) terms.add(token);
        }
      }
      if (group) matched++;
    }
    Matcher budget = BUDGET.matcher(query);
    boolean budgetHit = false;
    if (budget.find() && user.get("priceFromBdt") instanceof Number price && price.doubleValue() <= Double.parseDouble(budget.group(1))) {
      budgetHit = true;
      if (!reasons.contains("budget")) reasons.add("budget");
    }
    int groups = tokens.size() + (budgetHit ? 1 : 0);
    if (groups == 0) return null;
    double coverage = (double) (matched + (budgetHit ? 1 : 0)) / groups;
    if (coverage == 0) return null;
    String matchedText = "";
    String source = "profile";
    for (Field field : fields) {
      if (field.text.isBlank()) continue;
      String hay = field.text.toLowerCase(Locale.ROOT);
      if (terms.stream().anyMatch(hay::contains) && ("bio".equals(field.source) || "showcase".equals(field.source) || "post".equals(field.source))) {
        matchedText = trim(field.text, terms);
        source = field.source;
        break;
      }
    }
    if (matchedText.isEmpty()) {
      matchedText = trim(str(user.get("bio")), terms);
      source = matchedText.isEmpty() ? "profile" : "bio";
    }
    return new Hit(coverage * 0.8, reasons, terms, matchedText, source);
  }

  private List<Field> fields(Map<String, Object> user, Map<String, Object> db) {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("name", "name", str(user.get("name")) + " " + str(user.get("nameBn"))));
    fields.add(new Field("location", "location", str(user.get("city")) + " " + str(user.get("cityBn")) + " " + join(user.get("cityTerms"))));
    StringBuilder skills = new StringBuilder();
    for (String role : strings(user.get("roles"))) skills.append(role).append(' ').append(ROLES.getOrDefault(role, "")).append(' ');
    skills.append(join(user.get("instruments"))).append(' ').append(join(user.get("instrumentsBn")));
    if ("agency".equals(user.get("accountKind"))) {
      skills.append(" agency stage performance all-in-one show wedding concert band sound light মঞ্চ অনুষ্ঠান এজেন্সি স্টেজ প্যাকেজ ");
    }
    if ("female".equals(user.get("gender"))) skills.append(" female woman singer নারী মহিলা");
    if ("male".equals(user.get("gender"))) skills.append(" male man singer পুরুষ");
    fields.add(new Field("skill", "skills", skills.toString()));
    fields.add(new Field("genre", "genres", join(user.get("genres")) + " " + join(user.get("genresBn")) + " " + join(user.get("genreTerms"))));
    fields.add(new Field("skill", "bio", str(user.get("bio")) + " " + str(user.get("bioBn"))));
    for (Map<String, Object> col : collections(db, str(user.get("id")))) {
      fields.add(new Field("showcase", "showcase", str(col.get("title")) + " " + str(col.get("titleBn"))));
    }
    for (Map<String, Object> post : posts(db)) {
      if (!str(user.get("id")).equals(post.get("musicianId"))) continue;
      fields.add(new Field("showcase", "post", str(post.get("title")) + " " + str(post.get("text"))));
    }
    return fields;
  }

  private static List<String> tokens(String query) {
    String cleaned = TOKEN.matcher(query).replaceAll(" ");
    List<String> out = new ArrayList<>();
    for (String token : cleaned.split("\\s+")) {
      if (token.length() >= 2 && !STOP.contains(token) && !token.chars().allMatch(Character::isDigit)) out.add(token);
    }
    return out;
  }

  private static String trim(String text, List<String> terms) {
    String clean = text.replaceAll("\\s+", " ").trim();
    if (clean.length() <= 170) return clean;
    String lower = clean.toLowerCase(Locale.ROOT);
    int first = -1;
    for (String term : terms) {
      int at = lower.indexOf(term);
      if (at >= 0 && (first < 0 || at < first)) first = at;
    }
    int start = first < 0 ? 0 : Math.max(0, first - 50);
    String slice = clean.substring(start, Math.min(clean.length(), start + 170)).trim();
    return (start > 0 ? "…" : "") + slice + (start + 170 < clean.length() ? "…" : "");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> users(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("users");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> posts(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("posts");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> collections(Map<String, Object> db, String userId) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> item : (List<Map<String, Object>>) db.get("collections")) {
      if (userId.equals(item.get("musicianId"))) out.add(item);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> userOf(Map<String, Object> hit) {
    return (Map<String, Object>) hit.get("user");
  }

  private static String join(Object value) {
    if (!(value instanceof List<?> list)) return "";
    StringBuilder sb = new StringBuilder();
    for (Object item : list) sb.append(item).append(' ');
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<String> out = new ArrayList<>();
    for (Object item : list) out.add(String.valueOf(item));
    return out;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }

  private record Field(String reason, String source, String text) {}

  private record Hit(double score, List<String> reasons, List<String> terms, String matchedText, String matchedSource) {}
}
