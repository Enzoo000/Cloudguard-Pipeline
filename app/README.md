# Northbound Analytics (target app)

A thin Spring Boot REST API standing in for a small customer
analytics/reporting SaaS product. This is the app the CloudGuard
Pipeline scans, gates, and protects — it is not a real product, and it
is not meant to grow into one. Four vulnerabilities are seeded on
purpose; see attribution in `NOTICE`.

## Run it

```bash
cd app
mvn spring-boot:run
```

Or containerized:

```bash
cd app
docker build -t northbound-analytics:local .
docker run -d --name northbound -p 8080:8080 northbound-analytics:local
```

Either way it starts on `http://localhost:8080` with an in-memory H2
database, seeded via `data.sql` with three users and four reports. The
image is a multi-stage build (Maven/JDK only in the build stage, a
minimal JRE-alpine runtime), runs as a non-root `app` user, and exposes
`/actuator/health` for its `HEALTHCHECK` — none of that is a seeded
flaw, the four below are the only intentional ones.

## Seeded flaws

### 1. Broken authentication — unsalted MD5 password hashing

`LoginController` checks passwords against an unsalted MD5 hash. MD5 is
fast and has no per-user salt, so a leaked `app_user` table is crackable
in seconds via rainbow tables.

```bash
curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"AlicePassword!"}'
```

### 2. SQL injection — `GET /reports`

`ReportService#searchByCustomer` builds its query by string
concatenation. A normal request:

```bash
TOKEN=<paste the token from step 1>
curl -H "x-auth-token: $TOKEN" \
  "http://localhost:8080/reports?customerId=acme-co"
```

An injected request that returns every customer's reports regardless of
which one was asked for (use `-G --data-urlencode` so curl encodes the
quotes instead of mangling them):

```bash
curl -s -G -H "x-auth-token: $TOKEN" \
  --data-urlencode "customerId=x' OR '1'='1" \
  "http://localhost:8080/reports"
```

### 3. Path traversal — `GET /exports/**`

`ReportController#export` uses a `/exports/**` wildcard mapping and
passes the entire trailing path straight to the filesystem with no
sanitization — the same root cause behind real Spring path-traversal
CVEs that used a wildcard resource-serving mapping.

```bash
# Legitimate use
curl -H "x-auth-token: $TOKEN" http://localhost:8080/exports/sample-report.txt

# Escapes the exports directory and reads the app's own config —
# including the hardcoded JWT secret (flaw 4). --path-as-is stops curl
# from "helpfully" collapsing the ../ before it's even sent.
#
# Running via `mvn spring-boot:run` (config on the classpath under src/):
curl --path-as-is -H "x-auth-token: $TOKEN" \
  "http://localhost:8080/exports/../src/main/resources/application.properties"

# Running via Docker (config copied alongside the jar in /app/, the
# same directory `exports/` lives in — see app/Dockerfile):
curl --path-as-is -H "x-auth-token: $TOKEN" \
  "http://localhost:8080/exports/../application.properties"
```

### 4. Hardcoded secret — `application.properties`

`app.secret` signs every JWT this app issues and is committed in
plaintext instead of coming from Secrets Manager. Anyone who reads it
(including via flaw 3) can forge a valid token for any username without
ever knowing that user's password.

## What's deliberately not here

No frontend, no real business logic, no database beyond what's needed
to demonstrate these four flaws. See the root `README.md`'s
Problem/Solution and Architecture Diagram sections for how this app fits
into the larger pipeline.
