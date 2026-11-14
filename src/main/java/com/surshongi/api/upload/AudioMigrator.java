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

@Component
@Order(20)
public class AudioMigrator implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(AudioMigrator.class);
  private static final Map<String, String> TYPES = Map.of(
      ".wav", "audio/wav", ".mp3", "audio/mpeg", ".ogg", "audio/ogg",
      ".m4a", "audio/mp4", ".aac", "audio/aac", ".flac", "audio/flac", ".webm", "audio/webm");

  private final AudioFiles audio;
  private final Path root;

  public AudioMigrator(AudioFiles audio, @Value("${surshongi.upload-dir:uploads}") String uploadDir) {
    this.audio = audio;
    this.root = Path.of(uploadDir).toAbsolutePath().normalize();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.isDirectory(root)) return;
    int copied = 0;
    try (var files = Files.list(root)) {
      for (Path path : files.filter(file -> file.getFileName().toString().startsWith("audio-")).toList()) {
        String name = path.getFileName().toString();
        if (audio.find(name) != null) continue;
        try (InputStream in = Files.newInputStream(path)) {
          audio.save(name, type(name), in);
          copied++;
        } catch (Exception ex) {
          log.warn("Audio file {} was not stored in MongoDB: {}", name, ex.getMessage());
        }
      }
    } catch (Exception ex) {
      log.warn("Existing audio files were not copied into MongoDB: {}", ex.getMessage());
      return;
    }
    if (copied > 0) log.info("Stored {} existing audio file(s) in MongoDB", copied);
  }

  private static String type(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    return TYPES.getOrDefault(ext, "application/octet-stream");
  }
}
