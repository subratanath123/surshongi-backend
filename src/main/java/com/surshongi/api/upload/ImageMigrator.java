package com.surshongi.api.upload;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Copies images already saved on disk into MongoDB so a new server can still serve them. */
@Component
@Order(21)
public class ImageMigrator implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ImageMigrator.class);
  private static final Map<String, String> TYPES = Map.of(
      ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png",
      ".webp", "image/webp", ".gif", "image/gif", ".avif", "image/avif");

  private final ImageFiles images;
  private final Path root;

  public ImageMigrator(ImageFiles images, @Value("${surshongi.upload-dir:uploads}") String uploadDir) {
    this.images = images;
    this.root = Path.of(uploadDir).toAbsolutePath().normalize();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.isDirectory(root)) return;
    int copied = 0;
    try (var files = Files.list(root)) {
      for (Path path : files.filter(file -> file.getFileName().toString().startsWith("img-")).toList()) {
        String name = path.getFileName().toString();
        if (images.find(name) != null) continue;
        try (InputStream in = Files.newInputStream(path)) {
          images.save(name, type(name), in);
          copied++;
        } catch (Exception ex) {
          log.warn("Image file {} was not stored in MongoDB: {}", name, ex.getMessage());
        }
      }
    } catch (Exception ex) {
      log.warn("Existing image files were not copied into MongoDB: {}", ex.getMessage());
      return;
    }
    if (copied > 0) log.info("Stored {} existing image file(s) in MongoDB", copied);
  }

  private static String type(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    return TYPES.getOrDefault(ext, "image/jpeg");
  }
}
