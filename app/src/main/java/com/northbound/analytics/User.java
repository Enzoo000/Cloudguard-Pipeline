package com.northbound.analytics;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Looked up via Spring Data JPA (parameterized under the hood) — this
 * class is deliberately NOT where the seeded SQL injection lives. The
 * seeded flaw here is the password hash itself: unsalted MD5, seeded in
 * data.sql. See ReportService for the SQL injection flaw.
 *
 * Table is explicitly named app_user because USER is a reserved word in
 * the SQL standard (and rejected outright by some databases).
 */
@Entity
@Table(name = "app_user")
public class User {
  @Id
  public String username;
  public String hashedPassword;

  protected User() {}

  public User(String username, String hashedPassword) {
    this.username = username;
    this.hashedPassword = hashedPassword;
  }
}
