package com.surshongi.api.upload;

import com.mongodb.client.gridfs.model.GridFSFile;
import com.surshongi.api.session.SessionService;
import com.surshongi.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {
  private static final Set<String> AUDIO = Set.of(".wav", ".mp3", ".ogg", ".m4a", ".aac", ".flac", ".webm");
  private static final Set<String> VIDEO = Set.of(".mp4", ".webm", ".mov", ".m4v", ".mkv");
  private static final Set<String> IMAGE = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif");
  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry(".wav", "audio/wav"), Map.entry(".mp3", "audio/mpeg"), Map.entry(".ogg", "audio/ogg"),
      Map.entry(".m4a", "audio/mp4"), Map.entry(".aac", "audio/aac"), Map.entry(".flac", "audio/flac"),
      Map.entry(".mp4", "video/mp4"), Map.entry(".webm", "video/webm"), Map.entry(".mov", "video/quicktime"),
      Map.entry(".m4v", "video/mp4"), Map.entry(".mkv", "video/x-matroska"), Map.entry(".jpg", "image/jpeg"),
      Map.entry(".jpeg", "image/jpeg"), Map.entry(".png", "image/png"), Map.entry(".webp", "image/webp"),
      Map.entry(".gif", "image/gif"), Map.entry(".avif", "image/avif"));

  private final SessionService sessions;
  private final AudioFiles audio;
  private final ImageFiles images;
  private final Path root;

  public UploadController(SessionService sessions, AudioFiles audio, ImageFiles images, @Value("${surshongi.upload-dir:uploads}") String uploadDir) {
    this.sessions = sessions;
    this.audio = audio;
    this.images = images;
    this.root = Path.of(uploadDir).toAbsolutePath().normalize();
  }

  @PostMapping({"/community-upload", "/api/upload"})
  public Map<String, Object> upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws IOException {
    if (sessions.userId(request) == null) throw new ApiException(401, "Please sign in to upload files.");
    if (file == null || file.isEmpty()) throw new ApiException(400, "No file provided");
    String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
    String ext = extension(original);
    String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    boolean image = type.startsWith("image/") || IMAGE.contains(ext);
    boolean audioFile = type.startsWith("audio/") || AUDIO.contains(ext);
    boolean video = !audioFile && (type.startsWith("video/") || VIDEO.contains(ext));
    if (!image && !audioFile && !video) throw new ApiException(415, "Only audio, video, or image files can be uploaded.");
    long max = image ? 8L * 1024 * 1024 : video ? 100L * 1024 * 1024 : 60L * 1024 * 1024;
    String maxMb = image ? "8" : video ? "100" : "60";
    if (file.getSize() > max) throw new ApiException(413, "File is too large (max " + maxMb + " MB).");
    String safeExt = image ? (IMAGE.contains(ext) ? ext : ".jpg") : video ? (VIDEO.contains(ext) ? ext : ".mp4") : (AUDIO.contains(ext) ? ext : ".wav");
    String prefix = image ? "img" : video ? "video" : "audio";
    String name = prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
        + Integer.toString((int) (Math.random() * 60466176), 36) + safeExt;
    String contentType = TYPES.getOrDefault(safeExt, type.isBlank() ? "application/octet-stream" : type);
    if (image || !video) {
      try (InputStream body = file.getInputStream()) {
        if (image) images.save(name, contentType, body);
        else audio.save(name, contentType, body);
      }
    } else {
      Path target = root.resolve(name).normalize();
      if (!target.startsWith(root)) throw new ApiException(400, "Upload failed");
      try {
        Files.createDirectories(root);
        file.transferTo(target);
      } catch (IOException ex) {
        throw new ApiException(500, "The file could not be stored.");
      }
    }
    String display = original.replaceAll("\\.[^.]+$", "");
    return Map.of("url", "/uploads/" + name, "name", display, "kind", image ? "image" : video ? "video" : "audio");
  }

  @GetMapping("/uploads/{name}")
  public ResponseEntity<InputStreamResource> file(@PathVariable String name, HttpServletRequest request) throws IOException {
    if (!name.matches("[\\w.-]+") || name.startsWith(".")) return ResponseEntity.notFound().build();
    GridFSFile stored = name.startsWith("audio-") ? audio.find(name) : name.startsWith("img-") ? images.find(name) : null;
    if (stored != null) {
      String storedType = TYPES.getOrDefault(extension(name), "application/octet-stream");
      if (stored.getMetadata() != null && stored.getMetadata().getString("contentType") != null) {
        storedType = stored.getMetadata().getString("contentType");
      }
      GridFSFile file = stored;
      RangeSource source = name.startsWith("audio-")
          ? start -> audio.open(file.getObjectId(), start)
          : start -> images.open(file.getObjectId(), start);
      return bytes(request, stored.getLength(), storedType, source);
    }
    Path path = root.resolve(name).normalize();
    if (!path.startsWith(root) || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
    long size = Files.size(path);
    String type = TYPES.getOrDefault(extension(name), "application/octet-stream");
    Path file = path;
    return bytes(request, size, type, start -> openFile(file, start));
  }

  private ResponseEntity<InputStreamResource> bytes(HttpServletRequest request, long size, String type, RangeSource source) throws IOException {
    if (size <= 0) return ResponseEntity.notFound().build();
    String range = request.getHeader(HttpHeaders.RANGE);
    long start = 0;
    long end = size - 1;
    boolean partial = false;
    if (range != null && range.startsWith("bytes=")) {
      String[] parts = range.substring(6).split(",", 2)[0].split("-", 2);
      try {
        if (parts.length > 0 && !parts[0].isEmpty()) start = Long.parseLong(parts[0]);
        else if (parts.length > 1 && !parts[1].isEmpty()) start = Math.max(0, size - Long.parseLong(parts[1]));
        if (parts.length > 1 && !parts[1].isEmpty() && !parts[0].isEmpty()) end = Long.parseLong(parts[1]);
      } catch (NumberFormatException ex) {
        start = 0;
        end = size - 1;
      }
      start = Math.max(0, start);
      end = Math.min(size - 1, end);
      if (start > end) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
            .build();
      }
      partial = true;
    }
    long length = end - start + 1;
    InputStreamResource body = new InputStreamResource(limited(source.open(start), length));
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(type))
        .contentLength(length);
    if (partial) builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
    return builder.body(body);
  }

  private static InputStream openFile(Path path, long start) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
    raf.seek(start);
    return new FilterInputStream(new java.io.FileInputStream(raf.getFD())) {
      @Override
      public void close() throws IOException {
        super.close();
        raf.close();
      }
    };
  }

  private static InputStream limited(InputStream in, long length) {
    return new FilterInputStream(in) {
      private long left = length;

      @Override
      public int read() throws IOException {
        if (left <= 0) return -1;
        int value = super.read();
        if (value >= 0) left--;
        return value;
      }

      @Override
      public int read(byte[] buffer, int off, int len) throws IOException {
        if (left <= 0) return -1;
        int count = super.read(buffer, off, (int) Math.min(len, left));
        if (count > 0) left -= count;
        return count;
      }
    };
  }

  @FunctionalInterface
  private interface RangeSource {
    InputStream open(long start) throws IOException;
  }

  private static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
  }
}
