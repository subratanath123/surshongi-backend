package com.surshongi.api.mail;

import com.surshongi.api.community.CommunityRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MailService {
  private static final Logger log = LoggerFactory.getLogger(MailService.class);
  private final JavaMailSender mail;
  private final CommunityRepository repo;
  private final String user;
  private final String pass;
  private final String siteUrl;
  private volatile boolean disabled;

  public MailService(
      JavaMailSender mail,
      CommunityRepository repo,
      @Value("${surshongi.smtp-user:}") String user,
      @Value("${surshongi.smtp-pass:}") String pass,
      @Value("${surshongi.site-url:http://localhost:3000}") String siteUrl) {
    this.mail = mail;
    this.repo = repo;
    this.user = user == null ? "" : user.trim();
    this.pass = pass == null ? "" : pass.replaceAll("\\s+", "");
    this.siteUrl = siteUrl.replaceAll("/$", "");
  }

  public void orderPlaced(String to, String musicianName, String buyerName, String title, int price, int days, String description, String musicianId) {
    send(to, "New Order Placed by " + buyerName + ": \"" + title + "\" (৳" + price + ")",
        "<p>Hi <strong>" + esc(musicianName) + "</strong>,</p>"
            + "<p><strong>" + esc(buyerName) + "</strong> placed an order: <strong>" + esc(title) + "</strong></p>"
            + "<p>" + esc(description) + "</p>"
            + "<p>Budget ৳" + price + " · " + days + " days</p>"
            + "<p><a href=\"" + siteUrl + "/u/" + esc(musicianId) + "\">Open SurShongi</a></p>");
  }

  public void orderCompleted(String musicianEmail, String buyerEmail, String musicianName, String buyerName, String title, int price, int rating, String comment, String musicianId) {
    String html = "<p><strong>" + esc(buyerName) + "</strong> completed <strong>" + esc(title) + "</strong> (৳" + price + ") with <strong>" + esc(musicianName) + "</strong>.</p>"
        + "<p>" + "★".repeat(Math.max(1, Math.min(5, rating))) + " (" + rating + "/5)</p>"
        + "<p>" + esc(comment) + "</p>"
        + "<p><a href=\"" + siteUrl + "/u/" + esc(musicianId) + "\">View the review</a></p>";
    send(musicianEmail, buyerName + " completed order \"" + title + "\" and left a " + rating + "-star review", html);
    if (buyerEmail != null && !buyerEmail.equalsIgnoreCase(musicianEmail)) {
      send(buyerEmail, "Your order \"" + title + "\" with " + musicianName + " is completed", html);
    }
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
  public void unseenMessages() {
    if (!enabled()) return;
    try {
      List<Map<String, Object>> rows = repo.unseenMessages(Instant.now().minus(1, ChronoUnit.HOURS));
      for (Map<String, Object> row : rows) {
        send(str(row.get("recipient_email")),
            "Unseen message from " + row.get("sender_name") + " on SurShongi",
            "<p>Hi <strong>" + esc(str(row.get("recipient_name"))) + "</strong>,</p>"
                + "<p><strong>" + esc(str(row.get("sender_name"))) + "</strong> sent a message over an hour ago:</p>"
                + "<blockquote>" + esc(str(row.get("text"))) + "</blockquote>"
                + "<p><a href=\"" + siteUrl + "/u/" + esc(str(row.get("to_id"))) + "\">Reply on SurShongi</a></p>");
        repo.markMessageNotified(str(row.get("id")));
      }
    } catch (Exception ex) {
      log.warn("Unseen-message check failed: {}", ex.getMessage());
    }
  }

  private boolean enabled() {
    return !disabled && !user.isEmpty() && !pass.isEmpty();
  }

  private void send(String to, String subject, String html) {
    if (to == null || !to.contains("@") || !enabled()) return;
    try {
      MimeMessage message = mail.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
      helper.setFrom(user, "SurShongi (সুরসঙ্গী)");
      helper.setTo(to);
      helper.setSubject(subject);
      helper.setText(html, true);
      mail.send(message);
    } catch (Exception ex) {
      String message = ex.getMessage() == null ? "" : ex.getMessage();
      if (message.contains("535") || message.contains("Authentication")) disabled = true;
      log.warn("Email was not sent: {}", message);
    }
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String esc(String value) {
    return str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
