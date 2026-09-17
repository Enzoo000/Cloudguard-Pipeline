package com.northbound.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * SEEDED FLAW (broken authentication): passwords are checked against an
 * unsalted MD5 hash. MD5 is fast and unsalted, so leaked hashes (see
 * data.sql) are crackable via rainfall/rainbow tables in seconds. A real
 * implementation should use bcrypt/argon2 with a per-user salt.
 *
 * Adapted from ScaleSec/vulnado's LoginController (Apache License 2.0) —
 * see app/NOTICE.
 */
@RestController
public class LoginController {

  @Value("${app.secret}")
  private String secret;

  private final UserRepository users;

  public LoginController(UserRepository users) {
    this.users = users;
  }

  @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
  public LoginResponse login(@RequestBody LoginRequest input) {
    Optional<User> user = users.findById(input.username);
    if (user.isPresent() && md5(input.password).equals(user.get().hashedPassword)) {
      return new LoginResponse(AuthUtil.issueToken(secret, input.username));
    }
    throw new AuthUtil.Unauthorized("Access denied");
  }

  static String md5(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(input.getBytes());
      String hex = new BigInteger(1, digest).toString(16);
      while (hex.length() < 32) {
        hex = "0" + hex;
      }
      return hex;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class LoginRequest {
    public String username;
    public String password;
  }

  public static class LoginResponse {
    public String token;
    public LoginResponse(String token) { this.token = token; }
  }
}
