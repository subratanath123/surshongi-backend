package com.surshongi.api.community;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class CommunityRepository {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> DATE_KEYS = Set.of(
      "createdAt", "updatedAt", "readAt", "emailNotifiedAt", "paymentSubmittedAt",
      "paymentConfirmedAt", "deliveredAt", "completedAt");

  private final MongoTemplate mongo;

  public CommunityRepository(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public Map<String, Object> read() {
    Map<String, Object> db = new LinkedHashMap<>();
    db.put("users", mapAll("users", this::user, createdDesc()));
    db.put("collections", mapAll("showcase_items", this::collection, createdDesc()));
    db.put("posts", mapAll("community_posts", this::post, createdDesc()));
    Map<String, List<String>> follows = new LinkedHashMap<>();
    for (Document row : mongo.findAll(Document.class, "user_follows")) {
      follows.computeIfAbsent(str(row.get("followerId")), key -> new ArrayList<>()).add(str(row.get("musicianId")));
    }
    db.put("follows", follows);
    Map<String, Map<String, Integer>> ratings = new LinkedHashMap<>();
    for (Document row : mongo.findAll(Document.class, "user_ratings")) {
      ratings.computeIfAbsent(str(row.get("raterId")), key -> new LinkedHashMap<>())
          .put(str(row.get("musicianId")), num(row.get("rating")));
    }
    db.put("ratings", ratings);
    db.put("messages", mapAll("chat_messages", this::message, Sort.by(Sort.Direction.ASC, "createdAt")));
    db.put("orders", mapAll("service_orders", this::order, createdDesc()));
    db.put("reviews", mapAll("order_reviews", this::review, createdDesc()));
    return db;
  }

  public Map<String, Object> userById(String id) {
    Document doc = find("users", id);
    return doc == null ? null : user(doc);
  }

  public List<String> feedPreferences(String userId) {
    Document user = find("users", userId);
    if (user == null) return List.of();
    return strings(user.get("feedPreferences"));
  }

  public List<FeedSlot> pageFeed(String viewerId, String filter, String[] preferences, FeedCursor cursor, int limit) {
    String viewer = viewerId == null ? "" : viewerId;
    Set<String> prefs = new java.util.HashSet<>();
    if (preferences != null) {
      for (String preference : preferences) {
        if (preference != null && !preference.isBlank()) prefs.add(preference.toLowerCase(Locale.ROOT));
      }
    }
    Set<String> following = Set.of();
    if ("following".equals(filter) && !viewer.isBlank()) {
      following = new java.util.HashSet<>();
      Query query = Query.query(Criteria.where("followerId").is(viewer));
      for (Document row : mongo.find(query, Document.class, "user_follows")) following.add(str(row.get("musicianId")));
    }
    Map<String, Document> users = new LinkedHashMap<>();
    for (Document user : mongo.findAll(Document.class, "users")) users.put(idOf(user), user);
    List<FeedSlot> slots = new ArrayList<>();
    for (Document post : mongo.findAll(Document.class, "community_posts")) {
      Document user = users.get(str(post.get("musicianId")));
      if (user == null || !visible(user, viewer) || !matches(filter, post, user, following)) continue;
      int preferred = prefs.isEmpty() ? 0 : (matchesPreference(post, user, prefs) ? 1 : 0);
      int ratingCount = num(user.get("ratingCount"));
      int popular = num(user.get("followers")) + num(post.get("loves"));
      BigDecimal rating = decimal(user.get("rating"));
      long created = epoch(post.get("createdAt"));
      slots.add(new FeedSlot(idOf(post), preferred, ratingCount, popular, rating, created));
    }
    slots.sort(FEED_ORDER);
    if (cursor != null) slots.removeIf(slot -> !after(slot, cursor));
    if (slots.size() > limit) return new ArrayList<>(slots.subList(0, limit));
    return slots;
  }

  public List<Map<String, Object>> postsByIds(List<String> ids) {
    return byIds("community_posts", ids, this::post);
  }

  public List<Map<String, Object>> usersByIds(List<String> ids) {
    return byIds("users", ids, this::user);
  }

  public String publicName(String id) {
    Document user = find("users", id);
    return user == null ? null : strOrNull(user.get("name"));
  }

  public void upsertUser(Map<String, Object> user) {
    String id = str(user.get("id"));
    Document existing = find("users", id);
    Document doc = toDoc(id, user);
    if (existing == null) {
      doc.put("selfieVerified", false);
      doc.putIfAbsent("feedPreferences", List.of());
      doc.putIfAbsent("createdAt", new Date());
    } else {
      String oldAvatar = strOrNull(existing.get("avatarUrl"));
      String newAvatar = strOrNull(user.get("avatarUrl"));
      if (newAvatar == null) copy(doc, existing, "avatarUrl");
      if (strOrNull(user.get("coverUrl")) == null) copy(doc, existing, "coverUrl");
      boolean avatarChanged = newAvatar != null && (oldAvatar == null || !newAvatar.equals(oldAvatar));
      doc.put("selfieVerified", !avatarChanged && bool(existing.get("selfieVerified")));
      if (!user.containsKey("feedPreferences")) copy(doc, existing, "feedPreferences");
      if (existing.get("createdAt") != null) doc.put("createdAt", existing.get("createdAt"));
    }
    mongo.save(doc, "users");
  }

  public void updateGender(String userId, String gender) {
    Update update = new Update();
    if ("female".equals(gender) || "male".equals(gender)) update.set("gender", gender);
    else update.unset("gender");
    mongo.updateFirst(byId(userId), update, "users");
  }

  public void updateVisibility(String userId, boolean isPrivate) {
    mongo.updateFirst(byId(userId), new Update().set("isPrivate", isPrivate), "users");
  }

  public void updatePayment(String userId, String phone, String bkash) {
    Update update = new Update();
    optional(update, "phone", phone);
    optional(update, "bkashNumber", bkash);
    mongo.updateFirst(byId(userId), update, "users");
  }

  public void updateSocialLinks(String userId, String twitter, String facebook, String spotify, String youtube) {
    Update update = new Update();
    optional(update, "twitterUrl", twitter);
    optional(update, "facebookUrl", facebook);
    optional(update, "spotifyUrl", spotify);
    optional(update, "youtubeChannelUrl", youtube);
    mongo.updateFirst(byId(userId), update, "users");
  }

  public void updateFeedPreferences(String userId, List<String> preferences) {
    mongo.updateFirst(byId(userId), new Update().set("feedPreferences", preferences == null ? List.of() : preferences), "users");
  }

  public void verifySelfie(String userId, String avatarUrl) {
    mongo.updateFirst(byId(userId), new Update().set("avatarUrl", avatarUrl).set("selfieVerified", true), "users");
  }

  public void updatePhotos(String userId, String avatar, String cover, Integer position) {
    if (avatar == null && cover == null && position == null) return;
    Update update = new Update();
    if (avatar != null) update.set("avatarUrl", avatar).set("selfieVerified", false);
    if (cover != null) update.set("coverUrl", cover);
    if (position != null) update.set("coverPositionY", Math.max(0, Math.min(100, position)));
    mongo.updateFirst(byId(userId), update, "users");
  }

  public void insertShowcase(Map<String, Object> item) {
    mongo.save(toDoc(str(item.get("id")), item), "showcase_items");
  }

  public void insertPost(Map<String, Object> post) {
    Document doc = toDoc(str(post.get("id")), post);
    doc.put("loves", num(post.get("loves")));
    doc.put("lovedBy", strings(post.get("lovedBy")));
    mongo.save(doc, "community_posts");
  }

  public void updatePost(String postId, String musicianId, String category, String title, String text, String role, Integer price, String youtube, String audio, String listing, String image) {
    Update update = new Update()
        .set("category", category)
        .set("title", title)
        .set("titleBn", title)
        .set("text", text)
        .set("textBn", text)
        .set("roleTag", role);
    optional(update, "priceBdt", price);
    optional(update, "youtubeId", youtube);
    optional(update, "audioSrc", audio);
    if ("buy".equals(listing) || "sell".equals(listing)) update.set("listing", listing);
    else update.unset("listing");
    if (image != null) update.set("imageUrl", image);
    else update.unset("imageUrl");
    mongo.updateFirst(Query.query(Criteria.where("_id").is(postId).and("musicianId").is(musicianId)), update, "community_posts");
  }

  public void deletePost(String postId, String musicianId) {
    mongo.remove(Query.query(Criteria.where("_id").is(postId).and("musicianId").is(musicianId)), "community_posts");
  }

  public void toggleLove(String postId, String userId) {
    Document post = find("community_posts", postId);
    if (post == null) return;
    List<String> loved = new ArrayList<>(strings(post.get("lovedBy")));
    boolean already = loved.contains(userId);
    if (already) loved.remove(userId);
    else loved.add(userId);
    int loves = Math.max(0, num(post.get("loves")) + (already ? -1 : 1));
    mongo.updateFirst(byId(postId), new Update().set("loves", loves).set("lovedBy", loved), "community_posts");
  }

  public void toggleFollow(String followerId, String musicianId) {
    Query existing = Query.query(Criteria.where("followerId").is(followerId).and("musicianId").is(musicianId));
    if (mongo.exists(existing, "user_follows")) {
      mongo.remove(existing, "user_follows");
    } else {
      Document follow = new Document("_id", followerId + ":" + musicianId)
          .append("followerId", followerId)
          .append("musicianId", musicianId);
      mongo.save(follow, "user_follows");
    }
    long count = mongo.count(Query.query(Criteria.where("musicianId").is(musicianId)), "user_follows");
    mongo.updateFirst(byId(musicianId), new Update().set("followers", (int) count), "users");
  }

  public void rate(String raterId, String musicianId, int rating) {
    saveRating(raterId, musicianId, rating);
    refreshRating(musicianId, false);
  }

  public void insertMessage(Map<String, Object> message) {
    Document doc = toDoc(str(message.get("id")), message);
    if (!doc.containsKey("text")) doc.put("text", "");
    mongo.save(doc, "chat_messages");
  }

  public void savePushToken(String userId, String token) {
    Query query = Query.query(Criteria.where("token").is(token));
    Update update = new Update().set("userId", userId).set("updatedAt", new Date());
    mongo.upsert(query, update, "push_tokens");
  }

  public List<String> pushTokens(String userId) {
    List<String> tokens = new ArrayList<>();
    for (Document row : mongo.find(Query.query(Criteria.where("userId").is(userId)), Document.class, "push_tokens")) {
      String token = str(row.get("token"));
      if (!token.isBlank()) tokens.add(token);
    }
    return tokens;
  }

  public void deletePushToken(String token) {
    mongo.remove(Query.query(Criteria.where("token").is(token)), "push_tokens");
  }

  public void markSeen(String viewerId, String peerId) {
    Query query = Query.query(Criteria.where("toId").is(viewerId).and("fromId").is(peerId).and("readAt").is(null));
    mongo.updateMulti(query, new Update().set("readAt", new Date()), "chat_messages");
  }

  public void insertOrder(Map<String, Object> order) {
    Document doc = toDoc(str(order.get("id")), order);
    if (!doc.containsKey("description")) doc.put("description", "");
    if (!doc.containsKey("paymentStatus")) doc.put("paymentStatus", "none");
    mongo.save(doc, "service_orders");
  }

  public boolean submitTrx(String orderId, String buyerId, String trxId) {
    Query query = Query.query(Criteria.where("_id").is(orderId).and("buyerId").is(buyerId).and("paymentStatus").ne("confirmed"));
    Update update = new Update()
        .set("bkashTrxId", trxId)
        .set("paymentStatus", "submitted")
        .set("paymentSubmittedAt", new Date())
        .unset("paymentConfirmedAt")
        .unset("paymentDisputeNote");
    return mongo.updateFirst(query, update, "service_orders").getMatchedCount() > 0;
  }

  public boolean confirmTrx(String orderId, String musicianId) {
    Query query = Query.query(Criteria.where("_id").is(orderId)
        .and("musicianId").is(musicianId)
        .and("paymentStatus").is("submitted")
        .and("bkashTrxId").ne(null));
    Update update = new Update().set("paymentStatus", "confirmed").set("paymentConfirmedAt", new Date()).unset("paymentDisputeNote");
    return mongo.updateFirst(query, update, "service_orders").getMatchedCount() > 0;
  }

  public boolean disputeTrx(String orderId, String musicianId, String note) {
    Query query = Query.query(Criteria.where("_id").is(orderId).and("musicianId").is(musicianId).and("paymentStatus").is("submitted"));
    Update update = new Update().set("paymentStatus", "disputed").unset("paymentConfirmedAt");
    String clean = blankToNull(note);
    if (clean == null) update.unset("paymentDisputeNote");
    else update.set("paymentDisputeNote", clean);
    return mongo.updateFirst(query, update, "service_orders").getMatchedCount() > 0;
  }

  public void markDelivered(String orderId, String musicianId, String note, String url, String kind, String name) {
    Document order = find("service_orders", orderId);
    if (order == null || !musicianId.equals(str(order.get("musicianId"))) || !"active".equals(str(order.get("status")))) return;
    Update update = new Update().set("status", "delivered").set("deliveredAt", new Date());
    String cleanNote = blankToNull(note);
    String cleanUrl = blankToNull(url);
    if (cleanNote != null) update.set("deliveryNote", cleanNote);
    if (cleanUrl != null) {
      update.set("deliveryAttachmentUrl", cleanUrl);
      if (kind != null) update.set("deliveryAttachmentKind", kind);
    }
    String cleanName = blankToNull(name);
    if (cleanName != null) update.set("deliveryAttachmentName", cleanName);
    mongo.updateFirst(byId(orderId), update, "service_orders");
  }

  public String completeOrder(String orderId, String buyerId, int rating, String comment) {
    Document order = find("service_orders", orderId);
    if (order == null) return "Order not found.";
    if (!buyerId.equals(str(order.get("buyerId")))) return "Only the user who placed this order can mark it as completed.";
    String musicianId = str(order.get("musicianId"));
    int clamped = Math.max(1, Math.min(5, rating));
    String clean = comment == null || comment.isBlank() ? "Great collaboration and timely delivery!" : comment.trim();
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(orderId).and("buyerId").is(buyerId)),
        new Update().set("status", "completed").set("completedAt", new Date()),
        "service_orders");
    String reviewId = "rev-" + Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toString((int) (Math.random() * 36 * 36 * 36), 36);
    Document existingReview = mongo.findOne(Query.query(Criteria.where("orderId").is(orderId)), Document.class, "order_reviews");
    Document review = existingReview == null ? new Document() : existingReview;
    if (existingReview == null) review.put("_id", reviewId);
    review.put("id", review.get("_id"));
    review.put("orderId", orderId);
    review.put("buyerId", buyerId);
    review.put("musicianId", musicianId);
    review.put("rating", clamped);
    review.put("comment", clean);
    review.put("orderTitle", str(order.get("title")));
    review.put("priceBdt", num(order.get("priceBdt")));
    review.put("createdAt", new Date());
    mongo.save(review, "order_reviews");
    saveRating(buyerId, musicianId, clamped);
    refreshRating(musicianId, true);
    return null;
  }

  public Map<String, Object> orderRow(String id) {
    Document order = find("service_orders", id);
    if (order == null) return null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(order));
    row.put("buyer_id", str(order.get("buyerId")));
    row.put("musician_id", str(order.get("musicianId")));
    row.put("title", str(order.get("title")));
    row.put("price_bdt", num(order.get("priceBdt")));
    return row;
  }

  public Map<String, Object> findContact(String id) {
    Document user = find("users", id);
    if (user == null) return null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(user));
    row.put("name", str(user.get("name")));
    row.put("email", str(user.get("email")));
    return row;
  }

  public List<Map<String, Object>> unseenMessages(Instant cutoff) {
    Query query = Query.query(new Criteria().andOperator(
            Criteria.where("readAt").is(null),
            Criteria.where("emailNotifiedAt").is(null),
            Criteria.where("createdAt").lte(Date.from(cutoff))))
        .with(Sort.by(Sort.Direction.ASC, "createdAt"))
        .limit(20);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Document message : mongo.find(query, Document.class, "chat_messages")) {
      Document sender = find("users", str(message.get("fromId")));
      Document recipient = find("users", str(message.get("toId")));
      if (sender == null || recipient == null) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", idOf(message));
      row.put("text", str(message.get("text")));
      row.put("to_id", str(message.get("toId")));
      row.put("sender_name", str(sender.get("name")));
      row.put("recipient_name", str(recipient.get("name")));
      row.put("recipient_email", str(recipient.get("email")));
      rows.add(row);
    }
    return rows;
  }

  public void markMessageNotified(String id) {
    mongo.updateFirst(byId(id), new Update().set("emailNotifiedAt", new Date()), "chat_messages");
  }

  private void saveRating(String raterId, String musicianId, int rating) {
    Document existing = mongo.findOne(
        Query.query(Criteria.where("raterId").is(raterId).and("musicianId").is(musicianId)),
        Document.class,
        "user_ratings");
    Document doc = existing == null ? new Document("_id", raterId + ":" + musicianId) : existing;
    doc.put("raterId", raterId);
    doc.put("musicianId", musicianId);
    doc.put("rating", rating);
    doc.put("updatedAt", new Date());
    mongo.save(doc, "user_ratings");
  }

  private void refreshRating(String musicianId, boolean includeReviews) {
    List<Integer> ratings = new ArrayList<>();
    for (Document row : mongo.find(Query.query(Criteria.where("musicianId").is(musicianId)), Document.class, "user_ratings")) {
      ratings.add(num(row.get("rating")));
    }
    if (includeReviews) {
      for (Document row : mongo.find(Query.query(Criteria.where("musicianId").is(musicianId)), Document.class, "order_reviews")) {
        ratings.add(num(row.get("rating")));
      }
    }
    double average = 5.0;
    if (!ratings.isEmpty()) {
      double sum = 0;
      for (int rating : ratings) sum += rating;
      average = BigDecimal.valueOf(sum / ratings.size()).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
    mongo.updateFirst(byId(musicianId), new Update().set("ratingCount", ratings.size()).set("rating", average), "users");
  }

  private <T> List<T> mapAll(String collection, java.util.function.Function<Document, T> mapper, Sort sort) {
    List<T> rows = new ArrayList<>();
    for (Document doc : mongo.find(new Query().with(sort), Document.class, collection)) rows.add(mapper.apply(doc));
    return rows;
  }

  private List<Map<String, Object>> byIds(String collection, List<String> ids, java.util.function.Function<Document, Map<String, Object>> mapper) {
    if (ids.isEmpty()) return List.of();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Document doc : mongo.find(Query.query(Criteria.where("_id").in(ids)), Document.class, collection)) rows.add(mapper.apply(doc));
    return rows;
  }

  private Document find(String collection, String id) {
    if (id == null || id.isBlank()) return null;
    return mongo.findById(id, Document.class, collection);
  }

  private static Query byId(String id) {
    return Query.query(Criteria.where("_id").is(id));
  }

  private static Sort createdDesc() {
    return Sort.by(Sort.Direction.DESC, "createdAt");
  }

  private static boolean visible(Document user, String viewer) {
    return !bool(user.get("isPrivate")) || (!viewer.isBlank() && viewer.equals(idOf(user)));
  }

  private static boolean matches(String filter, Document post, Document user, Set<String> following) {
    return switch (filter == null ? "" : filter) {
      case "following" -> following.contains(str(post.get("musicianId")));
      case "vocal" -> hasRole(post, user, "vocalist");
      case "guitar" -> hasRole(post, user, "guitarist");
      case "percussion" -> hasRole(post, user, "percussion");
      case "folk" -> lower(strings(user.get("genreTerms"))).stream().anyMatch(term -> term.equals("folk") || term.equals("লোক"));
      default -> true;
    };
  }

  private static boolean hasRole(Document post, Document user, String role) {
    if (role.equals(lower(str(post.get("roleTag"))))) return true;
    return lower(strings(user.get("roles"))).contains(role);
  }

  private static boolean matchesPreference(Document post, Document user, Set<String> prefs) {
    if (prefs.contains(lower(str(post.get("roleTag"))))) return true;
    for (String role : lower(strings(user.get("roles")))) if (prefs.contains(role)) return true;
    for (String term : lower(strings(user.get("genreTerms")))) if (prefs.contains(term)) return true;
    for (String genre : lower(strings(user.get("genres")))) if (prefs.contains(genre)) return true;
    return false;
  }

  private static boolean after(FeedSlot slot, FeedCursor cursor) {
    int preferred = Integer.compare(slot.preferred(), cursor.preferred());
    if (preferred != 0) return preferred < 0;
    int ratingCount = Integer.compare(slot.ratingCount(), cursor.ratingCount());
    if (ratingCount != 0) return ratingCount < 0;
    int popular = Integer.compare(slot.popular(), cursor.popular());
    if (popular != 0) return popular < 0;
    int rating = slot.rating().compareTo(cursor.rating());
    if (rating != 0) return rating < 0;
    int created = Long.compare(slot.createdAtEpoch(), cursor.createdAtEpoch());
    if (created != 0) return created < 0;
    return slot.id().compareTo(cursor.id()) < 0;
  }

  private static final Comparator<FeedSlot> FEED_ORDER = (left, right) -> {
    int preferred = Integer.compare(right.preferred(), left.preferred());
    if (preferred != 0) return preferred;
    int ratingCount = Integer.compare(right.ratingCount(), left.ratingCount());
    if (ratingCount != 0) return ratingCount;
    int popular = Integer.compare(right.popular(), left.popular());
    if (popular != 0) return popular;
    int rating = right.rating().compareTo(left.rating());
    if (rating != 0) return rating;
    int created = Long.compare(right.createdAtEpoch(), left.createdAtEpoch());
    if (created != 0) return created;
    return right.id().compareTo(left.id());
  };

  private Map<String, Object> user(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    put(row, "email", strOrNull(doc.get("email")));
    put(row, "googleSub", strOrNull(doc.get("googleSub")));
    put(row, "avatarUrl", strOrNull(doc.get("avatarUrl")));
    put(row, "coverUrl", strOrNull(doc.get("coverUrl")));
    row.put("coverPositionY", doc.get("coverPositionY") == null ? 50 : num(doc.get("coverPositionY")));
    put(row, "phone", strOrNull(doc.get("phone")));
    put(row, "bkashNumber", strOrNull(doc.get("bkashNumber")));
    put(row, "twitterUrl", strOrNull(doc.get("twitterUrl")));
    put(row, "facebookUrl", strOrNull(doc.get("facebookUrl")));
    put(row, "spotifyUrl", strOrNull(doc.get("spotifyUrl")));
    put(row, "youtubeChannelUrl", strOrNull(doc.get("youtubeChannelUrl")));
    row.put("isPrivate", bool(doc.get("isPrivate")));
    row.put("selfieVerified", bool(doc.get("selfieVerified")));
    row.put("feedPreferences", strings(doc.get("feedPreferences")));
    row.put("profileCompleted", bool(doc.get("profileCompleted")));
    String accountKind = strOrNull(doc.get("accountKind"));
    if ("agency".equals(accountKind) || "member".equals(accountKind) || "musician".equals(accountKind)) {
      row.put("accountKind", accountKind);
    }
    row.put("name", str(doc.get("name")));
    row.put("nameBn", str(doc.get("nameBn")));
    row.put("city", str(doc.get("city")));
    row.put("cityBn", str(doc.get("cityBn")));
    row.put("cityTerms", strings(doc.get("cityTerms")));
    List<String> roles = doc.get("roles") == null ? null : strings(doc.get("roles"));
    row.put("roles", roles == null ? List.of("vocalist") : roles);
    String gender = strOrNull(doc.get("gender"));
    if ("female".equals(gender) || "male".equals(gender)) row.put("gender", gender);
    row.put("instruments", strings(doc.get("instruments")));
    row.put("instrumentsBn", strings(doc.get("instrumentsBn")));
    row.put("genres", strings(doc.get("genres")));
    row.put("genresBn", strings(doc.get("genresBn")));
    row.put("genreTerms", strings(doc.get("genreTerms")));
    row.put("languages", doc.get("languages") == null ? List.of("Bangla", "English") : strings(doc.get("languages")));
    row.put("languagesBn", doc.get("languagesBn") == null ? List.of("বাংলা", "ইংরেজি") : strings(doc.get("languagesBn")));
    row.put("languageTerms", doc.get("languageTerms") == null
        ? List.of("bangla", "বাংলা", "english", "ইংরেজি")
        : strings(doc.get("languageTerms")));
    row.put("bio", doc.get("bio") == null ? "" : str(doc.get("bio")));
    row.put("bioBn", doc.get("bioBn") == null ? "" : str(doc.get("bioBn")));
    putNum(row, "priceFromBdt", doc.get("priceFromBdt"));
    putNum(row, "turnaroundDays", doc.get("turnaroundDays"));
    putNum(row, "years", doc.get("years"));
    row.put("openToWork", bool(doc.get("openToWork")));
    put(row, "vocalRange", strOrNull(doc.get("vocalRange")));
    put(row, "vocalRangeBn", strOrNull(doc.get("vocalRangeBn")));
    if (doc.get("rangeTerms") != null) row.put("rangeTerms", strings(doc.get("rangeTerms")));
    put(row, "daw", strOrNull(doc.get("daw")));
    if (doc.get("dawTerms") != null) row.put("dawTerms", strings(doc.get("dawTerms")));
    row.put("mood", strings(doc.get("mood")));
    row.put("moodBn", strings(doc.get("moodBn")));
    row.put("moodTerms", strings(doc.get("moodTerms")));
    row.put("followers", num(doc.get("followers")));
    row.put("rating", doc.get("rating") == null ? 5.0 : decimal(doc.get("rating")).doubleValue());
    row.put("ratingCount", num(doc.get("ratingCount")));
    return row;
  }

  private Map<String, Object> collection(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    row.put("musicianId", str(doc.get("musicianId")));
    row.put("kind", str(doc.get("kind")));
    row.put("title", str(doc.get("title")));
    row.put("titleBn", str(doc.get("titleBn")));
    put(row, "youtubeId", strOrNull(doc.get("youtubeId")));
    put(row, "audioSrc", strOrNull(doc.get("audioSrc")));
    row.put("duration", strOrNull(doc.get("duration")) == null ? "Audio" : str(doc.get("duration")));
    row.put("createdAt", iso(doc.get("createdAt")));
    return row;
  }

  private Map<String, Object> post(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    row.put("musicianId", str(doc.get("musicianId")));
    row.put("category", str(doc.get("category")));
    row.put("title", str(doc.get("title")));
    row.put("titleBn", str(doc.get("titleBn")));
    row.put("text", str(doc.get("text")));
    row.put("textBn", str(doc.get("textBn")));
    row.put("roleTag", str(doc.get("roleTag")));
    putNum(row, "priceBdt", doc.get("priceBdt"));
    put(row, "listing", "buy".equals(str(doc.get("listing"))) ? "buy" : "sell".equals(str(doc.get("listing"))) ? "sell" : null);
    put(row, "imageUrl", strOrNull(doc.get("imageUrl")));
    put(row, "youtubeId", strOrNull(doc.get("youtubeId")));
    put(row, "audioSrc", strOrNull(doc.get("audioSrc")));
    row.put("loves", num(doc.get("loves")));
    row.put("lovedBy", strings(doc.get("lovedBy")));
    row.put("createdAt", iso(doc.get("createdAt")));
    return row;
  }

  private Map<String, Object> message(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    row.put("fromId", str(doc.get("fromId")));
    row.put("toId", str(doc.get("toId")));
    row.put("text", doc.get("text") == null ? "" : str(doc.get("text")));
    put(row, "attachmentUrl", strOrNull(doc.get("attachmentUrl")));
    put(row, "attachmentKind", strOrNull(doc.get("attachmentKind")));
    put(row, "attachmentName", strOrNull(doc.get("attachmentName")));
    put(row, "postId", strOrNull(doc.get("postId")));
    row.put("createdAt", iso(doc.get("createdAt")));
    put(row, "readAt", isoOrNull(doc.get("readAt")));
    put(row, "emailNotifiedAt", isoOrNull(doc.get("emailNotifiedAt")));
    return row;
  }

  private Map<String, Object> order(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    row.put("buyerId", str(doc.get("buyerId")));
    row.put("musicianId", str(doc.get("musicianId")));
    row.put("title", str(doc.get("title")));
    row.put("description", doc.get("description") == null ? "" : str(doc.get("description")));
    row.put("priceBdt", num(doc.get("priceBdt")));
    row.put("deliveryDays", num(doc.get("deliveryDays")));
    String status = strOrNull(doc.get("status"));
    row.put("status", status == null ? "active" : status);
    put(row, "bkashTrxId", strOrNull(doc.get("bkashTrxId")));
    String payment = strOrNull(doc.get("paymentStatus"));
    row.put("paymentStatus", "submitted".equals(payment) || "confirmed".equals(payment) || "disputed".equals(payment) ? payment : "none");
    put(row, "paymentSubmittedAt", isoOrNull(doc.get("paymentSubmittedAt")));
    put(row, "paymentConfirmedAt", isoOrNull(doc.get("paymentConfirmedAt")));
    put(row, "paymentDisputeNote", strOrNull(doc.get("paymentDisputeNote")));
    put(row, "attachmentUrl", strOrNull(doc.get("attachmentUrl")));
    put(row, "attachmentKind", strOrNull(doc.get("attachmentKind")));
    put(row, "attachmentName", strOrNull(doc.get("attachmentName")));
    put(row, "deliveryNote", strOrNull(doc.get("deliveryNote")));
    put(row, "deliveryAttachmentUrl", strOrNull(doc.get("deliveryAttachmentUrl")));
    put(row, "deliveryAttachmentKind", strOrNull(doc.get("deliveryAttachmentKind")));
    put(row, "deliveryAttachmentName", strOrNull(doc.get("deliveryAttachmentName")));
    row.put("createdAt", iso(doc.get("createdAt")));
    put(row, "deliveredAt", isoOrNull(doc.get("deliveredAt")));
    put(row, "completedAt", isoOrNull(doc.get("completedAt")));
    return row;
  }

  private Map<String, Object> review(Document doc) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", idOf(doc));
    row.put("orderId", str(doc.get("orderId")));
    row.put("buyerId", str(doc.get("buyerId")));
    row.put("musicianId", str(doc.get("musicianId")));
    row.put("rating", num(doc.get("rating")));
    row.put("comment", doc.get("comment") == null ? "" : str(doc.get("comment")));
    row.put("orderTitle", doc.get("orderTitle") == null ? "" : str(doc.get("orderTitle")));
    row.put("priceBdt", num(doc.get("priceBdt")));
    row.put("createdAt", iso(doc.get("createdAt")));
    return row;
  }

  private static Document toDoc(String id, Map<String, Object> source) {
    Document doc = new Document("_id", id).append("id", id);
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      if ("id".equals(entry.getKey()) || entry.getValue() == null) continue;
      Object value = entry.getValue();
      if (DATE_KEYS.contains(entry.getKey())) value = toDate(value);
      if (value != null) doc.put(entry.getKey(), value);
    }
    return doc;
  }

  private static Date toDate(Object value) {
    if (value instanceof Date date) return date;
    if (value instanceof Number number) return new Date(number.longValue());
    String text = strOrNull(value);
    if (text == null) return new Date();
    return parseDate(text);
  }

  private static void copy(Document target, Document source, String key) {
    if (source.get(key) != null) target.put(key, source.get(key));
  }

  private static Update optional(Update update, String key, Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) update.unset(key);
    else update.set(key, value);
    return update;
  }

  private static void put(Map<String, Object> row, String key, String value) {
    if (value != null && !value.isEmpty()) row.put(key, value);
  }

  private static void putNum(Map<String, Object> row, String key, Object value) {
    if (value instanceof Number) row.put(key, num(value));
  }

  private static String iso(Object value) {
    String text = isoOrNull(value);
    return text == null ? ISO.format(Instant.now()) : text;
  }

  private static String isoOrNull(Object value) {
    if (value == null) return null;
    if (value instanceof Date date) return ISO.format(date.toInstant());
    if (value instanceof Number number) return ISO.format(Instant.ofEpochMilli(number.longValue()));
    String text = strOrNull(value);
    return text;
  }

  private static Date parseDate(String text) {
    try {
      return Date.from(Instant.parse(text));
    } catch (RuntimeException ignored) {
      try {
        return Date.from(OffsetDateTime.parse(text).toInstant());
      } catch (RuntimeException ex) {
        return new Date();
      }
    }
  }

  private static long epoch(Object value) {
    if (value instanceof Date date) return date.getTime();
    if (value instanceof Number number) return number.longValue();
    String text = strOrNull(value);
    if (text == null) return 0L;
    return parseDate(text).getTime();
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<String> out = new ArrayList<>();
    for (Object item : list) if (item != null && !String.valueOf(item).isEmpty()) out.add(String.valueOf(item));
    return out;
  }

  private static List<String> lower(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String value : values) out.add(value.toLowerCase(Locale.ROOT));
    return out;
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static String idOf(Document doc) {
    Object id = doc.get("id");
    if (id == null) id = doc.get("_id");
    return id == null ? "" : String.valueOf(id);
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String strOrNull(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value);
    return text.isEmpty() ? null : text;
  }

  private static int num(Object value) {
    if (value instanceof Number number) return number.intValue();
    return 0;
  }

  private static boolean bool(Object value) {
    return Boolean.TRUE.equals(value) || "true".equals(value);
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).setScale(1, RoundingMode.HALF_UP);
    return BigDecimal.valueOf(5.0);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public record FeedSlot(String id, int preferred, int ratingCount, int popular, BigDecimal rating, long createdAtEpoch) {}

  public record FeedCursor(int preferred, int ratingCount, int popular, BigDecimal rating, long createdAtEpoch, String id) {}
}
