package com.northbound.analytics;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Report {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;
  public String customerId;
  public String title;
  public String body;

  protected Report() {}

  public Report(String customerId, String title, String body) {
    this.customerId = customerId;
    this.title = title;
    this.body = body;
  }
}
