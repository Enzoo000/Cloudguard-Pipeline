-- Seed data for Northbound Analytics.
-- Passwords are unsalted MD5 (see LoginController's seeded flaw):
--   admin    -> !!SuperSecretAdmin!!
--   alice    -> AlicePassword!
--   bob      -> BobPassword!
INSERT INTO app_user (username, hashed_password) VALUES
  ('admin', '7db6b0142556ef4080eca77ed2b40ee2'),
  ('alice', '6cb7a1593f419a1a3f31ba32ba2bd377'),
  ('bob',   'f4517ffef46740eb2f188a0b07f4680f');

INSERT INTO report (customer_id, title, body) VALUES
  ('acme-co',   'Q1 Signups',      'Acme Co acquired 412 new users in Q1.'),
  ('acme-co',   'Q2 Signups',      'Acme Co acquired 389 new users in Q2.'),
  ('globex',    'Churn Report',    'Globex churn rate held steady at 2.1%.'),
  ('initech',   'Revenue Summary', 'Initech MRR grew 14% quarter over quarter.');
