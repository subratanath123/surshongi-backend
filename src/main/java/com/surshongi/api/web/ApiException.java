package com.surshongi.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {
  private final int status;
  private final Map<String, Object> body;

  public ApiException(int status, String error) {
    super(error);
    this.status = status;
    this.body = Map.of("error", error);
  }

  public ApiException(int status, String error, List<String> missing) {
    super(error);
    this.status = status;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", error);
    payload.put("missing", missing);
    this.body = payload;
  }

  public int status() {
    return status;
  }

  public Map<String, Object> body() {
    return body;
  }
}
