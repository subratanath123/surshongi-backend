package com.surshongi.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SessionServiceTest {
  @Test
  void matchesNodeBase64UrlHmac() {
    SessionService sessions = new SessionService("test-secret", "http://localhost:3000");
    assertEquals("u-abc", sessions.verify("dS1hYmM.2000000000.cDyX6jKB-c4Yml5EHI3yJZDXPABB1xDSgB3A2YrGpBY"));
  }

  @Test
  void rejectsPlainUserId() {
    SessionService sessions = new SessionService("test-secret", "http://localhost:3000");
    assertNull(sessions.verify("u-abc"));
  }
}
