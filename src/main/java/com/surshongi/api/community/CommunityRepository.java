package com.surshongi.api.community;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CommunityRepository {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final JdbcTemplate jdbc;

  public CommunityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> read() {
    Map<String, Object> db = new LinkedHashMap<>();
    db.put("users", jdbc.query("SELECT * FROM users ORDER BY created_at DESC", (rs, n) -> user(rs)));
    db.put("collections", jdbc.query("SELECT * FROM showcase_items ORDER BY created_at DESC", (rs, n) -> collection(rs)));
    db.put("posts", jdbc.query("SELECT * FROM community_posts ORDER BY created_at DESC", (rs, n) -> post(rs)));
    Map<String, List<String>> follows = new LinkedHashMap<>();
    jdbc.query("SELECT follower_id, musician_id FROM user_follows", rs -> {
      follows.computeIfAbsent(rs.getString("follower_id"), key -> new ArrayList<>()).add(rs.getString("musician_id"));
    });
    db.put("follows", follows);
    Map<String, Map<String, Integer>> ratings = new LinkedHashMap<>();
    jdbc.query("SELECT rater_id, musician_id, rating FROM user_ratings", rs -> {
      ratings.computeIfAbsent(rs.getString("rater_id"), key -> new LinkedHashMap<>())
          .put(rs.getString("musician_id"), rs.getInt("rating"));
    });
    db.put("ratings", ratings);
    db.put("messages", jdbc.query("SELECT * FROM chat_messages ORDER BY created_at ASC", (rs, n) -> message(rs)));
    db.put("orders", jdbc.query("SELECT * FROM service_orders ORDER BY created_at DESC", (rs, n) -> order(rs)));
    db.put("reviews", jdbc.query("SELECT * FROM order_reviews ORDER BY created_at DESC", (rs, n) -> review(rs)));
    return db;
  }

  public String publicName(String id) {
    List<String> names = jdbc.query("SELECT name FROM users WHERE id = ?", (rs, n) -> rs.getString("name"), id);
    return names.isEmpty() ? null : names.get(0);
  }

  @Transactional
  public void upsertUser(Map<String, Object> user) {
    jdbc.update(
        """
        INSERT INTO users (
          id, email, google_sub, avatar_url, cover_url, profile_completed,
          name, name_bn, city, city_bn, city_terms,
          roles, instruments, instruments_bn, genres, genres_bn, genre_terms,
          languages, languages_bn, language_terms, bio, bio_bn,
          price_from_bdt, turnaround_days, years, open_to_work,
          mood, mood_bn, mood_terms, followers, rating, rating_count,
          phone, bkash_number, is_private, gender
        ) VALUES (
          ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, CAST(? AS jsonb),
          CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
          CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?,
          ?, ?, ?, ?,
          CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?,
          ?, ?, ?, ?
        )
        ON CONFLICT (id) DO UPDATE SET
          email = EXCLUDED.email,
          google_sub = EXCLUDED.google_sub,
          avatar_url = COALESCE(EXCLUDED.avatar_url, users.avatar_url),
          cover_url = COALESCE(EXCLUDED.cover_url, users.cover_url),
          profile_completed = EXCLUDED.profile_completed,
          name = EXCLUDED.name,
          name_bn = EXCLUDED.name_bn,
          city = EXCLUDED.city,
          city_bn = EXCLUDED.city_bn,
          city_terms = EXCLUDED.city_terms,
          roles = EXCLUDED.roles,
          instruments = EXCLUDED.instruments,
          instruments_bn = EXCLUDED.instruments_bn,
          genres = EXCLUDED.genres,
          genres_bn = EXCLUDED.genres_bn,
          genre_terms = EXCLUDED.genre_terms,
          bio = EXCLUDED.bio,
          bio_bn = EXCLUDED.bio_bn,
          price_from_bdt = EXCLUDED.price_from_bdt,
          turnaround_days = EXCLUDED.turnaround_days,
          open_to_work = EXCLUDED.open_to_work,
          followers = EXCLUDED.followers,
          rating = EXCLUDED.rating,
          rating_count = EXCLUDED.rating_count,
          phone = EXCLUDED.phone,
          bkash_number = EXCLUDED.bkash_number,
          is_private = EXCLUDED.is_private,
          gender = EXCLUDED.gender
        """,
        user.get("id"),
        user.get("email"),
        user.get("googleSub"),
        user.get("avatarUrl"),
        user.get("coverUrl"),
        Boolean.TRUE.equals(user.get("profileCompleted")),
        user.get("name"),
        user.get("nameBn"),
        user.get("city"),
        user.get("cityBn"),
        JsonText.array(strings(user.get("cityTerms"))),
        JsonText.array(strings(user.get("roles"))),
        JsonText.array(strings(user.get("instruments"))),
        JsonText.array(strings(user.get("instrumentsBn"))),
        JsonText.array(strings(user.get("genres"))),
        JsonText.array(strings(user.get("genresBn"))),
        JsonText.array(strings(user.get("genreTerms"))),
        JsonText.array(strings(user.get("languages"))),
        JsonText.array(strings(user.get("languagesBn"))),
        JsonText.array(strings(user.get("languageTerms"))),
        str(user.get("bio")),
        str(user.get("bioBn")),
        user.get("priceFromBdt"),
        user.get("turnaroundDays"),
        user.get("years"),
        user.get("openToWork") == null || Boolean.TRUE.equals(user.get("openToWork")),
        JsonText.array(strings(user.get("mood"))),
        JsonText.array(strings(user.get("moodBn"))),
        JsonText.array(strings(user.get("moodTerms"))),
        num(user.get("followers")),
        user.get("rating") instanceof Number n ? n.doubleValue() : 5.0,
        num(user.get("ratingCount")),
        user.get("phone"),
        user.get("bkashNumber"),
        Boolean.TRUE.equals(user.get("isPrivate")),
        user.get("gender"));
  }

  public void updateGender(String userId, String gender) {
    jdbc.update("UPDATE users SET gender = ? WHERE id = ?", gender, userId);
  }

  public void updateVisibility(String userId, boolean isPrivate) {
    jdbc.update("UPDATE users SET is_private = ? WHERE id = ?", isPrivate, userId);
  }

  public void updatePayment(String userId, String phone, String bkash) {
    jdbc.update("UPDATE users SET phone = ?, bkash_number = ? WHERE id = ?", blankToNull(phone), blankToNull(bkash), userId);
  }

  public void updatePhotos(String userId, String avatar, String cover, Integer position) {
    if (avatar != null) jdbc.update("UPDATE users SET avatar_url = ? WHERE id = ?", avatar, userId);
    if (cover != null) jdbc.update("UPDATE users SET cover_url = ? WHERE id = ?", cover, userId);
    if (position != null) {
      int clamped = Math.max(0, Math.min(100, position));
      jdbc.update("UPDATE users SET cover_position_y = ? WHERE id = ?", clamped, userId);
    }
  }

  public void insertShowcase(Map<String, Object> item) {
    jdbc.update(
        """
        INSERT INTO showcase_items (
          id, musician_id, kind, title, title_bn, youtube_id, audio_src, duration, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        item.get("id"), item.get("musicianId"), item.get("kind"), item.get("title"), item.get("titleBn"),
        item.get("youtubeId"), item.get("audioSrc"), item.get("duration"), timestamp(str(item.get("createdAt"))));
  }

  public void insertPost(Map<String, Object> post) {
    jdbc.update(
        """
        INSERT INTO community_posts (
          id, musician_id, category, title, title_bn, text, text_bn,
          role_tag, price_bdt, youtube_id, audio_src, loves, loved_by, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        """,
        post.get("id"), post.get("musicianId"), post.get("category"), post.get("title"), post.get("titleBn"),
        post.get("text"), post.get("textBn"), post.get("roleTag"), post.get("priceBdt"), post.get("youtubeId"),
        post.get("audioSrc"), num(post.get("loves")), JsonText.array(strings(post.get("lovedBy"))),
        timestamp(str(post.get("createdAt"))));
  }

  public void updatePost(String postId, String musicianId, String category, String title, String text, String role, Integer price, String youtube, String audio) {
    jdbc.update(
        """
        UPDATE community_posts
        SET category = ?, title = ?, title_bn = ?, text = ?, text_bn = ?, role_tag = ?,
            price_bdt = ?, youtube_id = ?, audio_src = ?
        WHERE id = ? AND musician_id = ?
        """,
        category, title, title, text, text, role, price, youtube, audio, postId, musicianId);
  }

  public void deletePost(String postId, String musicianId) {
    jdbc.update("DELETE FROM community_posts WHERE id = ? AND musician_id = ?", postId, musicianId);
  }

  public void toggleLove(String postId, String userId) {
    List<Map<String, Object>> rows = jdbc.query(
        "SELECT loves, loved_by::text AS loved_by FROM community_posts WHERE id = ?",
        (rs, n) -> Map.of("loves", rs.getInt("loves"), "lovedBy", rs.getString("loved_by")),
        postId);
    if (rows.isEmpty()) return;
    List<String> loved = new ArrayList<>(JsonText.strings(str(rows.get(0).get("lovedBy"))));
    boolean already = loved.contains(userId);
    if (already) loved.remove(userId);
    else loved.add(userId);
    int loves = Math.max(0, num(rows.get(0).get("loves")) + (already ? -1 : 1));
    jdbc.update("UPDATE community_posts SET loves = ?, loved_by = CAST(? AS jsonb) WHERE id = ?", loves, JsonText.array(loved), postId);
  }

  public void toggleFollow(String followerId, String musicianId) {
    Integer existing = jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_follows WHERE follower_id = ? AND musician_id = ?",
        Integer.class, followerId, musicianId);
    if (existing != null && existing > 0) {
      jdbc.update("DELETE FROM user_follows WHERE follower_id = ? AND musician_id = ?", followerId, musicianId);
    } else {
      jdbc.update("INSERT INTO user_follows (follower_id, musician_id) VALUES (?, ?) ON CONFLICT DO NOTHING", followerId, musicianId);
    }
    jdbc.update(
        "UPDATE users SET followers = (SELECT COUNT(*)::int FROM user_follows WHERE musician_id = ?) WHERE id = ?",
        musicianId, musicianId);
  }

  public void rate(String raterId, String musicianId, int rating) {
    jdbc.update(
        """
        INSERT INTO user_ratings (rater_id, musician_id, rating, updated_at)
        VALUES (?, ?, ?, NOW())
        ON CONFLICT (rater_id, musician_id) DO UPDATE SET rating = EXCLUDED.rating, updated_at = NOW()
        """,
        raterId, musicianId, rating);
    jdbc.update(
        """
        UPDATE users SET
          rating_count = (SELECT COUNT(*)::int FROM user_ratings WHERE musician_id = ?),
          rating = COALESCE((SELECT ROUND(AVG(rating)::numeric, 1) FROM user_ratings WHERE musician_id = ?), 5.0)
        WHERE id = ?
        """,
        musicianId, musicianId, musicianId);
  }

  public void insertMessage(Map<String, Object> message) {
    jdbc.update(
        """
        INSERT INTO chat_messages (
          id, from_id, to_id, text, attachment_url, attachment_kind, attachment_name, post_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        message.get("id"), message.get("fromId"), message.get("toId"), str(message.get("text")),
        message.get("attachmentUrl"), message.get("attachmentKind"), message.get("attachmentName"),
        message.get("postId"), timestamp(str(message.get("createdAt"))));
  }

  public void markSeen(String viewerId, String peerId) {
    jdbc.update(
        "UPDATE chat_messages SET read_at = NOW() WHERE to_id = ? AND from_id = ? AND read_at IS NULL",
        viewerId, peerId);
  }

  public void insertOrder(Map<String, Object> order) {
    jdbc.update(
        """
        INSERT INTO service_orders (
          id, buyer_id, musician_id, title, description, price_bdt, delivery_days, status,
          bkash_trx_id, payment_status, payment_submitted_at,
          attachment_url, attachment_kind, attachment_name, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        order.get("id"), order.get("buyerId"), order.get("musicianId"), order.get("title"),
        str(order.get("description")), num(order.get("priceBdt")), num(order.get("deliveryDays")),
        order.get("status"), order.get("bkashTrxId"), order.get("paymentStatus") == null ? "none" : order.get("paymentStatus"),
        order.get("paymentSubmittedAt") == null ? null : timestamp(str(order.get("paymentSubmittedAt"))),
        order.get("attachmentUrl"), order.get("attachmentKind"), order.get("attachmentName"),
        timestamp(str(order.get("createdAt"))));
  }

  public boolean submitTrx(String orderId, String buyerId, String trxId) {
    return jdbc.update(
        """
        UPDATE service_orders
        SET bkash_trx_id = ?, payment_status = 'submitted', payment_submitted_at = NOW(),
            payment_confirmed_at = NULL, payment_dispute_note = NULL
        WHERE id = ? AND buyer_id = ? AND payment_status <> 'confirmed'
        """,
        trxId, orderId, buyerId) > 0;
  }

  public boolean confirmTrx(String orderId, String musicianId) {
    return jdbc.update(
        """
        UPDATE service_orders
        SET payment_status = 'confirmed', payment_confirmed_at = NOW(), payment_dispute_note = NULL
        WHERE id = ? AND musician_id = ? AND payment_status = 'submitted' AND bkash_trx_id IS NOT NULL
        """,
        orderId, musicianId) > 0;
  }

  public boolean disputeTrx(String orderId, String musicianId, String note) {
    return jdbc.update(
        """
        UPDATE service_orders
        SET payment_status = 'disputed', payment_dispute_note = ?, payment_confirmed_at = NULL
        WHERE id = ? AND musician_id = ? AND payment_status = 'submitted'
        """,
        blankToNull(note), orderId, musicianId) > 0;
  }

  public void markDelivered(String orderId, String musicianId, String note, String url, String kind, String name) {
    jdbc.update(
        """
        UPDATE service_orders
        SET status = 'delivered',
            delivery_note = COALESCE(?, delivery_note),
            delivery_attachment_url = COALESCE(?, delivery_attachment_url),
            delivery_attachment_kind = COALESCE(?, delivery_attachment_kind),
            delivery_attachment_name = COALESCE(?, delivery_attachment_name),
            delivered_at = NOW()
        WHERE id = ? AND musician_id = ? AND status = 'active'
        """,
        blankToNull(note), blankToNull(url), kind, blankToNull(name), orderId, musicianId);
  }

  public String completeOrder(String orderId, String buyerId, int rating, String comment) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM service_orders WHERE id = ?", orderId);
    if (rows.isEmpty()) return "Order not found.";
    Map<String, Object> order = rows.get(0);
    if (!buyerId.equals(String.valueOf(order.get("buyer_id")))) {
      return "Only the user who placed this order can mark it as completed.";
    }
    String musicianId = String.valueOf(order.get("musician_id"));
    int clamped = Math.max(1, Math.min(5, rating));
    String clean = comment == null || comment.isBlank() ? "Great collaboration and timely delivery!" : comment.trim();
    jdbc.update(
        "UPDATE service_orders SET status = 'completed', completed_at = NOW() WHERE id = ? AND buyer_id = ?",
        orderId, buyerId);
    String reviewId = "rev-" + Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toString((int) (Math.random() * 36 * 36 * 36), 36);
    jdbc.update(
        """
        INSERT INTO order_reviews (
          id, order_id, buyer_id, musician_id, rating, comment, order_title, price_bdt, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
        ON CONFLICT (order_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, created_at = NOW()
        """,
        reviewId, orderId, buyerId, musicianId, clamped, clean, str(order.get("title")), num(order.get("price_bdt")));
    jdbc.update(
        """
        INSERT INTO user_ratings (rater_id, musician_id, rating, updated_at)
        VALUES (?, ?, ?, NOW())
        ON CONFLICT (rater_id, musician_id) DO UPDATE SET rating = EXCLUDED.rating, updated_at = NOW()
        """,
        buyerId, musicianId, clamped);
    jdbc.update(
        """
        UPDATE users SET
          rating_count = (
            SELECT COUNT(*)::int FROM (
              SELECT rater_id FROM user_ratings WHERE musician_id = ?
              UNION ALL
              SELECT buyer_id FROM order_reviews WHERE musician_id = ?
            ) combined
          ),
          rating = COALESCE((
            SELECT ROUND(AVG(rating)::numeric, 1) FROM (
              SELECT rating FROM user_ratings WHERE musician_id = ?
              UNION ALL
              SELECT rating FROM order_reviews WHERE musician_id = ?
            ) combined
          ), 5.0)
        WHERE id = ?
        """,
        musicianId, musicianId, musicianId, musicianId, musicianId);
    return null;
  }

  public Map<String, Object> orderRow(String id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, buyer_id, musician_id, title, price_bdt FROM service_orders WHERE id = ?", id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> findContact(String id) {
    List<Map<String, Object>> rows = jdbc.query(
        "SELECT id, name, email FROM users WHERE id = ?",
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", rs.getString("id"));
          row.put("name", rs.getString("name"));
          row.put("email", rs.getString("email"));
          return row;
        },
        id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Map<String, Object> user(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    put(row, "email", rs.getString("email"));
    put(row, "googleSub", rs.getString("google_sub"));
    put(row, "avatarUrl", rs.getString("avatar_url"));
    put(row, "coverUrl", rs.getString("cover_url"));
    int cover = rs.getInt("cover_position_y");
    row.put("coverPositionY", rs.wasNull() ? 50 : cover);
    put(row, "phone", rs.getString("phone"));
    put(row, "bkashNumber", rs.getString("bkash_number"));
    row.put("isPrivate", rs.getBoolean("is_private"));
    row.put("profileCompleted", rs.getBoolean("profile_completed"));
    row.put("name", rs.getString("name"));
    row.put("nameBn", rs.getString("name_bn"));
    row.put("city", rs.getString("city"));
    row.put("cityBn", rs.getString("city_bn"));
    row.put("cityTerms", list(rs, "city_terms"));
    List<String> roles = listOrNull(rs, "roles");
    row.put("roles", roles == null ? List.of("vocalist") : roles);
    String gender = rs.getString("gender");
    if ("female".equals(gender) || "male".equals(gender)) row.put("gender", gender);
    row.put("instruments", list(rs, "instruments"));
    row.put("instrumentsBn", list(rs, "instruments_bn"));
    row.put("genres", list(rs, "genres"));
    row.put("genresBn", list(rs, "genres_bn"));
    row.put("genreTerms", list(rs, "genre_terms"));
    List<String> languages = listOrNull(rs, "languages");
    row.put("languages", languages == null ? List.of("Bangla", "English") : languages);
    List<String> languagesBn = listOrNull(rs, "languages_bn");
    row.put("languagesBn", languagesBn == null ? List.of("বাংলা", "ইংরেজি") : languagesBn);
    List<String> languageTerms = listOrNull(rs, "language_terms");
    row.put("languageTerms", languageTerms == null ? List.of("bangla", "বাংলা", "english", "ইংরেজি") : languageTerms);
    row.put("bio", rs.getString("bio") == null ? "" : rs.getString("bio"));
    row.put("bioBn", rs.getString("bio_bn") == null ? "" : rs.getString("bio_bn"));
    putNum(row, "priceFromBdt", rs, "price_from_bdt");
    putNum(row, "turnaroundDays", rs, "turnaround_days");
    putNum(row, "years", rs, "years");
    row.put("openToWork", rs.getBoolean("open_to_work"));
    put(row, "vocalRange", rs.getString("vocal_range"));
    put(row, "vocalRangeBn", rs.getString("vocal_range_bn"));
    putList(row, "rangeTerms", rs, "range_terms");
    put(row, "daw", rs.getString("daw"));
    putList(row, "dawTerms", rs, "daw_terms");
    row.put("mood", list(rs, "mood"));
    row.put("moodBn", list(rs, "mood_bn"));
    row.put("moodTerms", list(rs, "mood_terms"));
    row.put("followers", rs.getInt("followers"));
    row.put("rating", rs.getBigDecimal("rating") == null ? 5.0 : rs.getBigDecimal("rating").doubleValue());
    row.put("ratingCount", rs.getInt("rating_count"));
    return row;
  }

  private static Map<String, Object> collection(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("musicianId", rs.getString("musician_id"));
    row.put("kind", rs.getString("kind"));
    row.put("title", rs.getString("title"));
    row.put("titleBn", rs.getString("title_bn"));
    put(row, "youtubeId", rs.getString("youtube_id"));
    put(row, "audioSrc", rs.getString("audio_src"));
    row.put("duration", rs.getString("duration") == null ? "Audio" : rs.getString("duration"));
    row.put("createdAt", iso(rs, "created_at"));
    return row;
  }

  private static Map<String, Object> post(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("musicianId", rs.getString("musician_id"));
    row.put("category", rs.getString("category"));
    row.put("title", rs.getString("title"));
    row.put("titleBn", rs.getString("title_bn"));
    row.put("text", rs.getString("text"));
    row.put("textBn", rs.getString("text_bn"));
    row.put("roleTag", rs.getString("role_tag"));
    putNum(row, "priceBdt", rs, "price_bdt");
    put(row, "youtubeId", rs.getString("youtube_id"));
    put(row, "audioSrc", rs.getString("audio_src"));
    row.put("loves", rs.getInt("loves"));
    row.put("lovedBy", list(rs, "loved_by"));
    row.put("createdAt", iso(rs, "created_at"));
    return row;
  }

  private static Map<String, Object> message(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("fromId", rs.getString("from_id"));
    row.put("toId", rs.getString("to_id"));
    row.put("text", rs.getString("text") == null ? "" : rs.getString("text"));
    put(row, "attachmentUrl", rs.getString("attachment_url"));
    put(row, "attachmentKind", rs.getString("attachment_kind"));
    put(row, "attachmentName", rs.getString("attachment_name"));
    put(row, "postId", rs.getString("post_id"));
    row.put("createdAt", iso(rs, "created_at"));
    put(row, "readAt", isoOrNull(rs, "read_at"));
    put(row, "emailNotifiedAt", isoOrNull(rs, "email_notified_at"));
    return row;
  }

  private static Map<String, Object> order(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("buyerId", rs.getString("buyer_id"));
    row.put("musicianId", rs.getString("musician_id"));
    row.put("title", rs.getString("title"));
    row.put("description", rs.getString("description") == null ? "" : rs.getString("description"));
    row.put("priceBdt", rs.getInt("price_bdt"));
    row.put("deliveryDays", rs.getInt("delivery_days"));
    row.put("status", rs.getString("status") == null ? "active" : rs.getString("status"));
    put(row, "bkashTrxId", rs.getString("bkash_trx_id"));
    String payment = rs.getString("payment_status");
    row.put("paymentStatus", "submitted".equals(payment) || "confirmed".equals(payment) || "disputed".equals(payment) ? payment : "none");
    put(row, "paymentSubmittedAt", isoOrNull(rs, "payment_submitted_at"));
    put(row, "paymentConfirmedAt", isoOrNull(rs, "payment_confirmed_at"));
    put(row, "paymentDisputeNote", rs.getString("payment_dispute_note"));
    put(row, "attachmentUrl", rs.getString("attachment_url"));
    put(row, "attachmentKind", rs.getString("attachment_kind"));
    put(row, "attachmentName", rs.getString("attachment_name"));
    put(row, "deliveryNote", rs.getString("delivery_note"));
    put(row, "deliveryAttachmentUrl", rs.getString("delivery_attachment_url"));
    put(row, "deliveryAttachmentKind", rs.getString("delivery_attachment_kind"));
    put(row, "deliveryAttachmentName", rs.getString("delivery_attachment_name"));
    row.put("createdAt", iso(rs, "created_at"));
    put(row, "deliveredAt", isoOrNull(rs, "delivered_at"));
    put(row, "completedAt", isoOrNull(rs, "completed_at"));
    return row;
  }

  private static Map<String, Object> review(ResultSet rs) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("orderId", rs.getString("order_id"));
    row.put("buyerId", rs.getString("buyer_id"));
    row.put("musicianId", rs.getString("musician_id"));
    row.put("rating", rs.getInt("rating"));
    row.put("comment", rs.getString("comment") == null ? "" : rs.getString("comment"));
    row.put("orderTitle", rs.getString("order_title") == null ? "" : rs.getString("order_title"));
    row.put("priceBdt", rs.getInt("price_bdt"));
    row.put("createdAt", iso(rs, "created_at"));
    return row;
  }

  private static List<String> list(ResultSet rs, String column) throws SQLException {
    List<String> value = listOrNull(rs, column);
    return value == null ? List.of() : value;
  }

  private static List<String> listOrNull(ResultSet rs, String column) throws SQLException {
    String json = rs.getString(column);
    if (rs.wasNull() || json == null) return null;
    return JsonText.strings(json);
  }

  private static void putList(Map<String, Object> row, String key, ResultSet rs, String column) throws SQLException {
    List<String> value = listOrNull(rs, column);
    if (value != null) row.put(key, value);
  }

  private static void put(Map<String, Object> row, String key, String value) {
    if (value != null && !value.isEmpty()) row.put(key, value);
  }

  private static void putNum(Map<String, Object> row, String key, ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    if (!rs.wasNull()) row.put(key, value);
  }

  private static String iso(ResultSet rs, String column) throws SQLException {
    String value = isoOrNull(rs, column);
    return value == null ? ISO.format(Instant.now()) : value;
  }

  private static String isoOrNull(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    if (ts == null) return null;
    return ISO.format(ts.toInstant());
  }

  private static Timestamp timestamp(String iso) {
    if (iso == null || iso.isBlank()) return Timestamp.from(Instant.now());
    return Timestamp.from(Instant.parse(iso));
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) if (item != null) out.add(String.valueOf(item));
      return out;
    }
    return List.of();
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int num(Object value) {
    if (value instanceof Number n) return n.intValue();
    return 0;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
