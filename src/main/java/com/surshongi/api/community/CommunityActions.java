package com.surshongi.api.community;

import com.surshongi.api.mail.MailService;
import com.surshongi.api.push.PushService;
import com.surshongi.api.web.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CommunityActions {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Pattern YOUTUBE = Pattern.compile("^[\\w-]{11}$");
  private static final Pattern SELFIE = Pattern.compile("^/uploads/img-[\\w.-]+$");
  private static final Pattern MEDIA = Pattern.compile("^/(uploads|media|covers)/[\\w.-]+$");
  private static final Set<String> CATEGORIES = Set.of("work", "offering", "collab", "gear");
  private static final Set<String> VIDEO = Set.of(".mp4", ".webm", ".mov", ".m4v", ".mkv");
  private static final int BIO_MIN = 30;
  private static final int BIO_MAX = 600;

  private final CommunityRepository repo;
  private final MailService mail;
  private final PushService push;

  public CommunityActions(CommunityRepository repo, MailService mail, PushService push) {
    this.repo = repo;
    this.mail = mail;
    this.push = push;
  }

  public Map<String, Object> databaseFor(String viewerId) {
    return scope(repo.read(), viewerId);
  }

  public Map<String, Object> handle(String me, Map<String, Object> body) {
    String action = str(body.get("action"));
    Map<String, Object> db = repo.read();
    Map<String, Object> meUser = find(users(db), me);
    if (meUser == null) throw new ApiException(401, "Your session is no longer valid. Please sign in again.");

    return switch (action) {
      case "completeProfile" -> completeProfile(me, body, db, meUser);
      case "updatePaymentContacts" -> payment(me, body);
      case "updateSocialLinks" -> socialLinks(me, body);
      case "updateProfileVisibility" -> visibility(me, body);
      case "updateGender" -> gender(me, body);
      case "updateProfilePhotos" -> photos(me, body);
      case "verifySelfie" -> selfie(me, body);
      case "updateFeedPreferences" -> preferences(me, body);
      case "addCollectionItem" -> collection(me, body);
      case "createPost" -> createPost(me, body, meUser);
      case "updatePost" -> updatePost(me, body, db);
      case "deletePost" -> deletePost(me, body, db);
      case "toggleLove" -> love(me, body, db);
      case "toggleFollow" -> follow(me, body, db);
      case "rateMusician" -> rate(me, body, db);
      case "sendMessage" -> message(me, body, db);
      case "savePushToken" -> savePushToken(me, body);
      case "markMessagesSeen" -> seen(me, body);
      case "createOrder" -> order(me, body, db);
      case "markOrderDelivered" -> deliver(me, body, db);
      case "completeOrderAndReview" -> complete(me, body);
      case "submitBkashTrx" -> submitTrx(me, body, db);
      case "confirmBkashPayment" -> confirmTrx(me, body, db);
      case "disputeBkashPayment" -> disputeTrx(me, body, db);
      default -> throw new ApiException(400, "Unknown action");
    };
  }

  private Map<String, Object> completeProfile(String me, Map<String, Object> body, Map<String, Object> db, Map<String, Object> meUser) {
    String kind = str(body.get("accountKind"));
    if (!"agency".equals(kind) && !"member".equals(kind)) kind = "musician";
    String name = clean(body.get("name"), 80);
    String city = clean(body.get("city"), 60);
    String bio = clean(body.get("bio"), BIO_MAX);
    String instrumentsRaw = clean(body.get("instruments"), 300);
    String genresRaw = clean(body.get("genres"), 300);
    int price = digits(body.get("price"));
    String youtubeId = "member".equals(kind) ? null : youtube(body.get("youtubeId"));
    String audioSrc = "member".equals(kind) ? null : media(body.get("audioSrc"));
    boolean hasShowcase = collections(db).stream().anyMatch(item -> me.equals(item.get("musicianId")));
    List<String> missing = new ArrayList<>();
    if (name.isEmpty()) missing.add("name");
    if (city.isEmpty()) missing.add("city");
    if (bio.length() < BIO_MIN) missing.add("bio");
    if (!"member".equals(kind)) {
      if (instrumentsRaw.isEmpty()) missing.add("instruments");
      if (genresRaw.isEmpty()) missing.add("genres");
      if (price <= 0) missing.add("price");
    }
    if ("musician".equals(kind) && youtubeId == null && audioSrc == null && !hasShowcase) missing.add("showcase");
    if (!missing.isEmpty()) {
      throw new ApiException(400, "Profile is incomplete: " + String.join(", ", missing) + ".", missing);
    }
    String role = clean(body.get("role"), 40);
    String gender = "female".equals(body.get("gender")) || "male".equals(body.get("gender")) ? str(body.get("gender")) : null;
    List<String> instruments = "member".equals(kind) ? List.of() : split(instrumentsRaw, "agency".equals(kind) ? "Stage" : (role.isEmpty() ? "vocalist" : role));
    List<String> genres = "member".equals(kind) ? List.of() : split(genresRaw, "agency".equals(kind) ? "Stage show" : "Folk");
    boolean sings = "musician".equals(kind) && ("vocalist".equals(role) || instruments.stream().anyMatch(item -> {
      String lower = item.toLowerCase();
      return lower.contains("vocal") || lower.contains("singer") || item.contains("কণ্ঠ");
    }));
    if (sings && gender == null) throw new ApiException(400, "Choose female or male so the singer cards can list you.");
    Map<String, Object> updated = new LinkedHashMap<>(meUser);
    String avatar = media(body.get("avatarUrl"));
    String cover = media(body.get("coverUrl"));
    if (avatar != null) updated.put("avatarUrl", avatar);
    if (cover != null) updated.put("coverUrl", cover);
    if (body.containsKey("phone")) putOrRemove(updated, "phone", phone(body.get("phone")));
    if (body.containsKey("bkashNumber")) putOrRemove(updated, "bkashNumber", phone(body.get("bkashNumber")));
    if (body.containsKey("isPrivate")) updated.put("isPrivate", Boolean.TRUE.equals(body.get("isPrivate")));
    updated.put("profileCompleted", true);
    updated.put("name", name);
    updated.put("nameBn", name);
    updated.put("city", city);
    updated.put("cityBn", city);
    updated.put("cityTerms", List.of(city.toLowerCase()));
    updated.put("accountKind", kind);
    updated.put("roles", "musician".equals(kind) ? List.of(role.isEmpty() ? "vocalist" : role) : List.of());
    if (gender == null || !"musician".equals(kind)) updated.remove("gender");
    else updated.put("gender", gender);
    updated.put("instruments", instruments);
    updated.put("instrumentsBn", instruments);
    updated.put("genres", genres);
    updated.put("genresBn", genres);
    updated.put("genreTerms", genres.stream().map(String::toLowerCase).toList());
    updated.put("bio", bio);
    updated.put("bioBn", bio);
    if ("member".equals(kind)) updated.remove("priceFromBdt");
    else updated.put("priceFromBdt", Math.min(price, 10_000_000));
    if (updated.get("turnaroundDays") == null) updated.put("turnaroundDays", 4);
    updated.put("openToWork", !"member".equals(kind));
    repo.upsertUser(updated);
    String now = now();
    if (youtubeId != null) {
      String title = clean(body.get("youtubeTitle"), 140);
      repo.insertShowcase(showcase(id("col-yt-"), me, "youtube", title.isEmpty() ? name + " — YouTube Performance" : title, youtubeId, null, "Video", now));
    }
    if (audioSrc != null) {
      String title = clean(body.get("audioTitle"), 140);
      repo.insertShowcase(showcase(id("col-au-"), me, "audio", title.isEmpty() ? name + " — Audio Showcase" : title, null, audioSrc, "Audio", now));
    }
    Map<String, Object> fresh = find(users(repo.read()), me);
    return respond(me, Map.of("user", fresh == null ? updated : fresh));
  }

  private Map<String, Object> payment(String me, Map<String, Object> body) {
    repo.updatePayment(me, phone(body.get("phone")), phone(body.get("bkashNumber")));
    return userResponse(me);
  }

  private Map<String, Object> socialLinks(String me, Map<String, Object> body) {
    repo.updateSocialLinks(
        me,
        socialUrl(body.get("twitterUrl"), "twitter.com", "x.com"),
        socialUrl(body.get("facebookUrl"), "facebook.com", "fb.com"),
        socialUrl(body.get("spotifyUrl"), "spotify.com", "open.spotify.com"),
        socialUrl(body.get("youtubeChannelUrl"), "youtube.com", "music.youtube.com", "youtu.be"));
    return userResponse(me);
  }

  private Map<String, Object> visibility(String me, Map<String, Object> body) {
    repo.updateVisibility(me, Boolean.TRUE.equals(body.get("isPrivate")));
    return userResponse(me);
  }

  private Map<String, Object> gender(String me, Map<String, Object> body) {
    if (!"female".equals(body.get("gender")) && !"male".equals(body.get("gender"))) {
      throw new ApiException(400, "Choose female or male.");
    }
    repo.updateGender(me, str(body.get("gender")));
    return userResponse(me);
  }

  private static final Set<String> FEED_PREFS = Set.of(
      "vocalist", "guitarist", "percussion", "flute", "producer", "lyricist",
      "folk", "rabindra", "nazrul", "pop");

  private Map<String, Object> preferences(String me, Map<String, Object> body) {
    List<String> chosen = new ArrayList<>();
    if (body.get("preferences") instanceof List<?> list) {
      for (Object item : list) {
        String value = str(item);
        if (FEED_PREFS.contains(value) && !chosen.contains(value) && chosen.size() < 8) chosen.add(value);
      }
    }
    repo.updateFeedPreferences(me, chosen);
    return userResponse(me);
  }

  private Map<String, Object> selfie(String me, Map<String, Object> body) {
    String avatar = str(body.get("avatarUrl")).trim();
    if (!SELFIE.matcher(avatar).matches()) {
      throw new ApiException(400, "Take the photo with your camera.");
    }
    repo.verifySelfie(me, avatar);
    return userResponse(me);
  }

  private Map<String, Object> photos(String me, Map<String, Object> body) {
    Integer position = null;
    if (body.get("coverPositionY") instanceof Number n && Double.isFinite(n.doubleValue())) {
      position = Math.min(100, Math.max(0, (int) Math.round(n.doubleValue())));
    }
    repo.updatePhotos(me, media(body.get("avatarUrl")), media(body.get("coverUrl")), position);
    return userResponse(me);
  }

  private Map<String, Object> collection(String me, Map<String, Object> body) {
    String kind = "youtube".equals(body.get("kind")) ? "youtube" : "audio";
    String youtubeId = youtube(body.get("youtubeId"));
    String audioSrc = media(body.get("audioSrc"));
    String title = clean(body.get("title"), 140);
    if (title.isEmpty()) throw new ApiException(400, "Title is required.");
    if ("youtube".equals(kind) ? youtubeId == null : audioSrc == null) {
      throw new ApiException(400, "A valid YouTube link or audio file is required.");
    }
    Map<String, Object> item = showcase(
        id("col-"), me, kind, title,
        "youtube".equals(kind) ? youtubeId : null,
        "audio".equals(kind) ? audioSrc : null,
        textOr(clean(body.get("duration"), 20), "youtube".equals(kind) ? "Video" : "Audio"),
        now());
    repo.insertShowcase(item);
    return respond(me, Map.of("item", item));
  }

  private Map<String, Object> createPost(String me, Map<String, Object> body, Map<String, Object> meUser) {
    String title = clean(body.get("title"), 140);
    String text = clean(body.get("text"), 2000);
    if (title.isEmpty() || text.isEmpty()) throw new ApiException(400, "Title and text are required.");
    Integer price = positive(body.get("priceBdt"));
    List<String> roles = strings(meUser.get("roles"));
    Map<String, Object> post = new LinkedHashMap<>();
    post.put("id", id("post-"));
    post.put("musicianId", me);
    String category = CATEGORIES.contains(str(body.get("category"))) ? str(body.get("category")) : "work";
    post.put("category", category);
    boolean gear = "gear".equals(category);
    String listing = gear ? ("buy".equals(str(body.get("listing"))) ? "buy" : "sell") : null;
    if (listing != null) post.put("listing", listing);
    if ("sell".equals(listing)) putIf(post, "imageUrl", media(body.get("imageUrl")));
    post.put("title", title);
    post.put("titleBn", title);
    post.put("text", text);
    post.put("textBn", text);
    post.put("roleTag", gear ? "" : textOr(clean(body.get("roleTag"), 40), roles.isEmpty() ? "vocalist" : roles.get(0)));
    if (price != null) post.put("priceBdt", price);
    putIf(post, "youtubeId", youtube(body.get("youtubeId")));
    if (!gear) putIf(post, "audioSrc", media(body.get("audioSrc")));
    post.put("loves", 1);
    post.put("lovedBy", List.of(me));
    post.put("createdAt", now());
    repo.insertPost(post);
    return respond(me, Map.of("post", post));
  }

  private Map<String, Object> updatePost(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> existing = find(posts(db), str(body.get("postId")));
    if (existing == null) throw new ApiException(404, "Post not found.");
    if (!me.equals(existing.get("musicianId"))) throw new ApiException(403, "Only the author of this post can edit it.");
    String title = clean(body.get("title"), 140);
    String text = clean(body.get("text"), 2000);
    if (title.isEmpty() || text.isEmpty()) throw new ApiException(400, "Title and text are required.");
    String category = CATEGORIES.contains(str(body.get("category"))) ? str(body.get("category")) : str(existing.get("category"));
    boolean gear = "gear".equals(category);
    String listing = gear ? ("buy".equals(str(body.get("listing"))) ? "buy" : "sell") : null;
    repo.updatePost(
        str(existing.get("id")), me,
        category,
        title, text,
        gear ? "" : textOr(clean(body.get("roleTag"), 40), str(existing.get("roleTag"))),
        positive(body.get("priceBdt")),
        youtube(body.get("youtubeId")),
        gear ? null : media(body.get("audioSrc")),
        listing,
        "sell".equals(listing) ? media(body.get("imageUrl")) : null);
    return respond(me, Map.of());
  }

  private Map<String, Object> deletePost(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> existing = find(posts(db), str(body.get("postId")));
    if (existing == null) throw new ApiException(404, "Post not found.");
    if (!me.equals(existing.get("musicianId"))) throw new ApiException(403, "Only the author of this post can delete it.");
    repo.deletePost(str(existing.get("id")), me);
    return respond(me, Map.of());
  }

  private Map<String, Object> love(String me, Map<String, Object> body, Map<String, Object> db) {
    if (find(posts(db), str(body.get("postId"))) == null) throw new ApiException(404, "Post not found.");
    repo.toggleLove(str(body.get("postId")), me);
    return respond(me, Map.of());
  }

  private Map<String, Object> follow(String me, Map<String, Object> body, Map<String, Object> db) {
    String musicianId = str(body.get("musicianId"));
    if (find(users(db), musicianId) == null) throw new ApiException(404, "Musician not found.");
    if (me.equals(musicianId)) throw new ApiException(400, "You cannot follow yourself.");
    repo.toggleFollow(me, musicianId);
    return respond(me, Map.of());
  }

  private Map<String, Object> rate(String me, Map<String, Object> body, Map<String, Object> db) {
    String musicianId = str(body.get("musicianId"));
    int rating = (int) Math.round(number(body.get("rating")));
    if (find(users(db), musicianId) == null) throw new ApiException(404, "Musician not found.");
    if (me.equals(musicianId)) throw new ApiException(400, "You cannot rate yourself.");
    if (rating < 1 || rating > 5) throw new ApiException(400, "Rating must be between 1 and 5.");
    repo.rate(me, musicianId, rating);
    return respond(me, Map.of());
  }

  private Map<String, Object> message(String me, Map<String, Object> body, Map<String, Object> db) {
    String toId = str(body.get("toId"));
    if (me.equals(toId)) throw new ApiException(400, "Self chat is restricted.");
    if (find(users(db), toId) == null) throw new ApiException(404, "Recipient not found.");
    String attachmentUrl = media(body.get("attachmentUrl"));
    String kind = kind(attachmentUrl, body.get("attachmentKind"));
    String name = textOrNull(clean(body.get("attachmentName"), 140));
    String raw = clean(body.get("text"), 4000);
    String postId = clean(body.get("postId"), 80);
    Map<String, Object> referenced = postId.isEmpty() ? null : find(posts(db), postId);
    if (!postId.isEmpty() && (referenced == null || !toId.equals(referenced.get("musicianId")))) {
      throw new ApiException(400, "That post cannot be attached to this chat.");
    }
    String text = !raw.isEmpty() ? raw : attachmentUrl != null
        ? "📎 " + ("video".equals(kind) ? "Video" : "Audio") + ": " + (name == null ? "attachment" : name)
        : "";
    if (text.isEmpty() && attachmentUrl == null && referenced == null) throw new ApiException(400, "Message is empty.");
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("id", id("msg-") + Integer.toString((int) (Math.random() * 46656), 36));
    msg.put("fromId", me);
    msg.put("toId", toId);
    msg.put("text", text);
    putIf(msg, "attachmentUrl", attachmentUrl);
    putIf(msg, "attachmentKind", kind);
    putIf(msg, "attachmentName", name);
    if (referenced != null) msg.put("postId", referenced.get("id"));
    msg.put("createdAt", now());
    repo.insertMessage(msg);
    Map<String, Object> sender = find(users(db), me);
    String senderName = sender == null ? "SurShongi" : clean(sender.get("name"), 80);
    push.notifyNewMessage(toId, senderName.isEmpty() ? "SurShongi" : senderName, text);
    return respond(me, Map.of("message", msg));
  }

  private Map<String, Object> savePushToken(String me, Map<String, Object> body) {
    String token = str(body.get("token")).trim();
    if (!token.matches("[\\x21-\\x7E]{80,4096}")) throw new ApiException(400, "Push token is not valid.");
    repo.savePushToken(me, token);
    return respond(me, Map.of());
  }

  private Map<String, Object> seen(String me, Map<String, Object> body) {
    if (!(body.get("peerId") instanceof String peer) || peer.isBlank()) throw new ApiException(400, "peerId is required.");
    repo.markSeen(me, peer);
    return respond(me, Map.of());
  }

  private Map<String, Object> order(String me, Map<String, Object> body, Map<String, Object> db) {
    String musicianId = str(body.get("musicianId"));
    if (me.equals(musicianId)) throw new ApiException(400, "Cannot place an order with yourself.");
    if (find(users(db), musicianId) == null) throw new ApiException(404, "Musician not found.");
    String title = clean(body.get("title"), 140);
    if (title.isEmpty()) throw new ApiException(400, "Order title is required.");
    int price = (int) Math.round(number(body.get("priceBdt")));
    if (price <= 0) throw new ApiException(400, "Order price must be greater than 0.");
    String rawTrx = clean(body.get("bkashTrxId"), 40);
    String trx = JsonText.trxId(rawTrx);
    if (!rawTrx.isEmpty() && trx == null) throw new ApiException(400, "bKash TrxID should be 6 to 20 letters or numbers.");
    String createdAt = now();
    String attachmentUrl = media(body.get("attachmentUrl"));
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", id("ord-"));
    order.put("buyerId", me);
    order.put("musicianId", musicianId);
    order.put("title", title);
    order.put("description", clean(body.get("description"), 2000));
    order.put("priceBdt", price);
    int days = (int) Math.round(number(body.get("deliveryDays")));
    order.put("deliveryDays", Math.min(90, Math.max(1, days == 0 ? 3 : days)));
    order.put("status", "active");
    putIf(order, "bkashTrxId", trx);
    order.put("paymentStatus", trx == null ? "none" : "submitted");
    if (trx != null) order.put("paymentSubmittedAt", createdAt);
    putIf(order, "attachmentUrl", attachmentUrl);
    putIf(order, "attachmentKind", kind(attachmentUrl, body.get("attachmentKind")));
    putIf(order, "attachmentName", textOrNull(clean(body.get("attachmentName"), 140)));
    order.put("createdAt", createdAt);
    repo.insertOrder(order);
    String suffix = trx == null ? "" : " · bKash TrxID: " + trx;
    String description = str(order.get("description"));
    Map<String, Object> chat = new LinkedHashMap<>();
    chat.put("id", id("msg-ord-"));
    chat.put("fromId", me);
    chat.put("toId", musicianId);
    chat.put("text", "📦 Order Placed: \"" + title + "\" — ৳" + price + " (" + order.get("deliveryDays") + " days)"
        + (description.isEmpty() ? "" : " · " + description) + suffix);
    putIf(chat, "attachmentUrl", order.get("attachmentUrl"));
    putIf(chat, "attachmentKind", order.get("attachmentKind"));
    putIf(chat, "attachmentName", order.get("attachmentName"));
    chat.put("createdAt", now());
    repo.insertMessage(chat);
    Map<String, Object> buyer = repo.findContact(me);
    Map<String, Object> musician = repo.findContact(musicianId);
    if (buyer != null && musician != null) {
      mail.orderPlaced(str(musician.get("email")), str(musician.get("name")), str(buyer.get("name")), title, price,
          num(order.get("deliveryDays")), description, musicianId);
    }
    return respond(me, Map.of("order", order));
  }

  private Map<String, Object> deliver(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> order = find(orders(db), str(body.get("orderId")));
    if (order == null) throw new ApiException(404, "Order not found.");
    if (!me.equals(order.get("musicianId"))) throw new ApiException(403, "Only the musician on this order can deliver it.");
    if (!"active".equals(order.get("status"))) throw new ApiException(400, "This order is not active.");
    String note = textOrNull(clean(body.get("deliveryNote"), 2000));
    String url = media(body.get("deliveryAttachmentUrl"));
    String kind = kind(url, body.get("deliveryAttachmentKind"));
    String name = textOrNull(clean(body.get("deliveryAttachmentName"), 140));
    repo.markDelivered(str(order.get("id")), me, note, url, kind, name);
    Map<String, Object> chat = new LinkedHashMap<>();
    chat.put("id", id("msg-dlv-"));
    chat.put("fromId", me);
    chat.put("toId", order.get("buyerId"));
    chat.put("text", "📦 Order Delivered: \"" + order.get("title") + "\"" + (note == null ? "" : " — " + note));
    putIf(chat, "attachmentUrl", url);
    putIf(chat, "attachmentKind", kind);
    putIf(chat, "attachmentName", name);
    chat.put("createdAt", now());
    repo.insertMessage(chat);
    return respond(me, Map.of());
  }

  private Map<String, Object> complete(String me, Map<String, Object> body) {
    int rating = (int) Math.round(number(body.get("rating")));
    if (rating < 1 || rating > 5) throw new ApiException(400, "Rating must be between 1 and 5.");
    String error = repo.completeOrder(str(body.get("orderId")), me, rating, clean(body.get("comment"), 2000));
    if (error != null) throw new ApiException(403, error);
    Map<String, Object> order = repo.orderRow(str(body.get("orderId")));
    if (order != null) {
      Map<String, Object> buyer = repo.findContact(me);
      Map<String, Object> musician = repo.findContact(str(order.get("musician_id")));
      if (buyer != null && musician != null) {
        mail.orderCompleted(
            str(musician.get("email")), str(buyer.get("email")), str(musician.get("name")), str(buyer.get("name")),
            str(order.get("title")), num(order.get("price_bdt")), rating, clean(body.get("comment"), 2000), str(musician.get("id")));
      }
    }
    return respond(me, Map.of());
  }

  private Map<String, Object> submitTrx(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> order = find(orders(db), str(body.get("orderId")));
    if (order == null) throw new ApiException(404, "Order not found.");
    if (!me.equals(order.get("buyerId"))) throw new ApiException(403, "Only the buyer can submit the bKash TrxID.");
    if ("confirmed".equals(order.get("paymentStatus"))) {
      throw new ApiException(400, "This payment is already confirmed and cannot be changed.");
    }
    String trx = JsonText.trxId(clean(body.get("bkashTrxId"), 40));
    if (trx == null) throw new ApiException(400, "bKash TrxID should be 6 to 20 letters or numbers.");
    if (!repo.submitTrx(str(order.get("id")), me, trx)) throw new ApiException(400, "Could not save the bKash TrxID.");
    return respond(me, Map.of());
  }

  private Map<String, Object> confirmTrx(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> order = find(orders(db), str(body.get("orderId")));
    if (order == null) throw new ApiException(404, "Order not found.");
    if (!me.equals(order.get("musicianId"))) throw new ApiException(403, "Only the seller can confirm this payment.");
    if (!"submitted".equals(order.get("paymentStatus")) || order.get("bkashTrxId") == null) {
      throw new ApiException(400, "The buyer has not submitted a bKash TrxID yet.");
    }
    if (!repo.confirmTrx(str(order.get("id")), me)) throw new ApiException(400, "Could not confirm this payment.");
    return respond(me, Map.of());
  }

  private Map<String, Object> disputeTrx(String me, Map<String, Object> body, Map<String, Object> db) {
    Map<String, Object> order = find(orders(db), str(body.get("orderId")));
    if (order == null) throw new ApiException(404, "Order not found.");
    if (!me.equals(order.get("musicianId"))) throw new ApiException(403, "Only the seller can dispute this payment.");
    if (!"submitted".equals(order.get("paymentStatus"))) {
      throw new ApiException(400, "There is no submitted bKash TrxID to dispute.");
    }
    if (!repo.disputeTrx(str(order.get("id")), me, clean(body.get("note"), 240))) {
      throw new ApiException(400, "Could not record the dispute.");
    }
    return respond(me, Map.of());
  }

  private Map<String, Object> userResponse(String me) {
    Map<String, Object> fresh = repo.read();
    Map<String, Object> user = find(users(fresh), me);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("db", scope(fresh, me));
    body.put("user", user);
    return body;
  }

  private Map<String, Object> respond(String me, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("db", scope(repo.read(), me));
    body.putAll(extra);
    return body;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> scope(Map<String, Object> db, String viewerId) {
    Map<String, Object> scoped = new LinkedHashMap<>();
    List<Map<String, Object>> users = new ArrayList<>();
    for (Map<String, Object> user : users(db)) {
      Map<String, Object> copy = new LinkedHashMap<>(user);
      if (!copy.get("id").equals(viewerId)) {
        copy.remove("email");
        copy.remove("googleSub");
        copy.remove("feedPreferences");
      }
      users.add(copy);
    }
    scoped.put("users", users);
    scoped.put("collections", db.get("collections"));
    scoped.put("posts", db.get("posts"));
    scoped.put("follows", db.get("follows"));
    scoped.put("ratings", db.get("ratings"));
    List<Map<String, Object>> messages = new ArrayList<>();
    if (viewerId != null) {
      for (Map<String, Object> message : messages(db)) {
        if (viewerId.equals(message.get("fromId")) || viewerId.equals(message.get("toId"))) messages.add(message);
      }
    }
    scoped.put("messages", messages);
    List<Map<String, Object>> orders = new ArrayList<>();
    if (viewerId != null) {
      for (Map<String, Object> order : orders(db)) {
        if (viewerId.equals(order.get("buyerId")) || viewerId.equals(order.get("musicianId"))) orders.add(order);
      }
    }
    scoped.put("orders", orders);
    scoped.put("reviews", db.get("reviews"));
    return scoped;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> users(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("users");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> collections(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("collections");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> posts(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("posts");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> messages(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("messages");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> orders(Map<String, Object> db) {
    return (List<Map<String, Object>>) db.get("orders");
  }

  private static Map<String, Object> find(List<Map<String, Object>> rows, String id) {
    if (id == null || id.isEmpty()) return null;
    for (Map<String, Object> row : rows) if (id.equals(row.get("id"))) return row;
    return null;
  }

  private static Map<String, Object> showcase(String id, String musicianId, String kind, String title, String youtube, String audio, String duration, String createdAt) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", id);
    item.put("musicianId", musicianId);
    item.put("kind", kind);
    item.put("title", title);
    item.put("titleBn", title);
    putIf(item, "youtubeId", youtube);
    putIf(item, "audioSrc", audio);
    item.put("duration", duration);
    item.put("createdAt", createdAt);
    return item;
  }

  private static String kind(String url, Object explicit) {
    if (url == null) return null;
    if ("video".equals(explicit) || "audio".equals(explicit)) return str(explicit);
    String lower = url.toLowerCase();
    for (String ext : VIDEO) {
      if (lower.endsWith(ext) || lower.contains("/video-")) return "video";
    }
    return "audio";
  }

  private static String youtube(Object value) {
    String text = str(value);
    return YOUTUBE.matcher(text).matches() ? text : null;
  }

  private static String media(Object value) {
    String text = str(value).trim();
    if (text.isEmpty()) return null;
    if (MEDIA.matcher(text).matches() || text.matches("^https://\\S+$")) return text.length() > 1000 ? text.substring(0, 1000) : text;
    return null;
  }

  private static String socialUrl(Object value, String... hosts) {
    String text = str(value).trim();
    if (text.isEmpty()) return null;
    if (text.startsWith("http://")) text = "https://" + text.substring("http://".length());
    if (!text.startsWith("https://")) text = "https://" + text;
    if (text.length() > 300) throw new ApiException(400, "That link is too long.");
    java.net.URI uri;
    try {
      uri = java.net.URI.create(text);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(400, "Enter a full https link.");
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (host.startsWith("www.")) host = host.substring(4);
    boolean allowed = false;
    for (String item : hosts) {
      if (host.equals(item) || host.endsWith("." + item)) allowed = true;
    }
    if (!allowed || uri.getScheme() == null) throw new ApiException(400, "That link is not from the right site.");
    return text;
  }

  private static String phone(Object value) {
    if (!(value instanceof String text)) return null;
    String trimmed = text.trim().replaceAll("[^\\d+\\-\\s]", "");
    if (trimmed.length() > 24) trimmed = trimmed.substring(0, 24);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static List<String> split(String raw, String fallback) {
    List<String> list = new ArrayList<>();
    for (String part : raw.split(",")) {
      String item = part.trim();
      if (!item.isEmpty() && list.size() < 12) list.add(item);
    }
    return list.isEmpty() ? List.of(fallback) : list;
  }

  private static String clean(Object value, int max) {
    if (!(value instanceof String text)) return "";
    String trimmed = text.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static int digits(Object value) {
    String raw = str(value).replaceAll("[^\\d]", "");
    if (raw.isEmpty()) return 0;
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static double number(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(str(value));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static Integer positive(Object value) {
    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() <= 0) return null;
    return (int) Math.round(n.doubleValue());
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) out.add(String.valueOf(item));
      return out;
    }
    return List.of();
  }

  private static String now() {
    return ISO.format(Instant.now());
  }

  private static String id(String prefix) {
    return prefix + Long.toString(System.currentTimeMillis(), 36);
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int num(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static String textOr(String value, String fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String textOrNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static void putIf(Map<String, Object> map, String key, Object value) {
    if (value != null) map.put(key, value);
  }

  private static void putOrRemove(Map<String, Object> map, String key, String value) {
    if (value == null) map.remove(key);
    else map.put(key, value);
  }
}
