package com.surshongi.api.community;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonText {
  private static final Pattern STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
  private static final Pattern TRX = Pattern.compile("^[A-Za-z0-9]{6,20}$");

  private JsonText() {}

  public static List<String> strings(String json) {
    if (json == null || json.isBlank() || "null".equals(json)) return new ArrayList<>();
    List<String> out = new ArrayList<>();
    Matcher matcher = STRING.matcher(json);
    while (matcher.find()) out.add(unescape(matcher.group(1)));
    return out;
  }

  public static String array(List<String> items) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(escape(items.get(i))).append('"');
    }
    return sb.append(']').toString();
  }

  public static String trxId(String value) {
    if (value == null) return null;
    String compact = value.replaceAll("\\s+", "").toUpperCase();
    return TRX.matcher(compact).matches() ? compact : null;
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static String unescape(String value) {
    return value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\");
  }
}
