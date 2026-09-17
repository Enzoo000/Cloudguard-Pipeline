package com.northbound.analytics;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.crypto.SecretKey;

/**
 * JWT issue/verify, adapted from ScaleSec/vulnado's User.token() /
 * User.assertAuth() pattern (Apache License 2.0) — see app/NOTICE.
 *
 * SEEDED FLAW: the signing secret is read from a hardcoded value in
 * application.properties (app.secret) instead of a secrets manager or
 * environment-injected value. Anyone with read access to the source or
 * the deployed jar can forge a valid token for any username.
 */
public class AuthUtil {

  public static String issueToken(String secret, String username) {
    SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());
    return Jwts.builder().subject(username).signWith(key).compact();
  }

  public static void assertValid(String secret, String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    } catch (Exception e) {
      throw new Unauthorized("Access denied");
    }
  }

  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public static class Unauthorized extends RuntimeException {
    public Unauthorized(String message) {
      super(message);
    }
  }
}
