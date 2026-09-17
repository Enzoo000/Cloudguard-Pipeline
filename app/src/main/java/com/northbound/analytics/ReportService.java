package com.northbound.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SEEDED FLAW (SQL injection): searchByCustomer builds its query by
 * string concatenation instead of a parameterized statement. A
 * customerId like `x' OR '1'='1` returns every customer's reports, and a
 * UNION-based payload can pull data out of unrelated tables (e.g. the
 * users table's password hashes).
 *
 * This is the one deliberately vulnerable query in the app — everything
 * else (UserRepository, Report's normal JPA access) goes through Spring
 * Data JPA, which parameterizes by default.
 */
@Service
public class ReportService {

  private final JdbcTemplate jdbcTemplate;

  public ReportService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Report> searchByCustomer(String customerId) {
    String sql = "SELECT customer_id, title, body FROM report WHERE customer_id = '"
        + customerId + "'";
    return jdbcTemplate.query(sql, (rs, rowNum) -> new Report(
        rs.getString("customer_id"),
        rs.getString("title"),
        rs.getString("body")
    ));
  }
}
