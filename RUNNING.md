# Running the RFP Platform

Three options: full Docker stack, local dev (each service separately), or DB-only via Docker + local services.

---

## Option A — Full Docker Stack (simplest)

Requires Docker Desktop running.

```bash
# Create a .env file in the repo root with your API key:
echo "RFP_LLM_API_KEY=your-anthropic-key" > .env
echo "JWT_SECRET=at-least-32-chars-secret-here" >> .env

# Start everything (postgres + backend + frontend)
docker compose up --build
```

| Service  | URL                    |
|----------|------------------------|
| Frontend | http://localhost:3000  |
| Backend  | http://localhost:8080  |
| Postgres | localhost:5432         |

Stop with `docker compose down`. Add `-v` to also wipe the database volume.

---

## Option B — Local Dev (faster iteration)

### 1. Database (Docker)

```bash
docker compose up db -d
```

This starts only Postgres on port 5432 with:
- DB name: `rfp`
- User: `rfp`
- Password: `rfp`

### 2. Backend (Spring Boot)

Requires Java 21. On Windows use the Microsoft JDK:

```bash
# Set JAVA_HOME if needed (Windows path)
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot

# From repo root
cd backend
..\mvnw spring-boot:run
```

Or with Maven directly:

```bash
C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd spring-boot:run
```

Backend starts on **http://localhost:8080**. Flyway runs migrations automatically on startup.

Required environment variables (set in a `.env` file or export before running):

| Variable          | Description                          | Example                        |
|-------------------|--------------------------------------|--------------------------------|
| `RFP_LLM_API_KEY` | Anthropic or OpenAI API key          | `sk-ant-...`                   |
| `JWT_SECRET`      | At least 32 characters               | `my-super-secret-32-char-key!` |
| `RFP_LLM_PROVIDER`| `anthropic` or `openai`             | `anthropic` (default)          |
| `RFP_LLM_MODEL`   | Model name                           | `claude-sonnet-4-6` (default)  |

### 3. Frontend (Next.js)

Requires Node.js 18+.

```bash
cd frontend
npm install       # first time only
npm run dev
```

Frontend starts on **http://localhost:3000**.

---

## Option C — Quickstart with `.env` file

Create `.env` in the repo root:

```env
RFP_LLM_API_KEY=sk-ant-your-key-here
JWT_SECRET=replace-this-with-32-plus-chars!!
RFP_LLM_PROVIDER=anthropic
RFP_LLM_MODEL=claude-sonnet-4-6
```

Then either `docker compose up --build` (Option A) or start each service individually (Option B).

---

## First-time setup

1. Register an account at http://localhost:3000 (creates a `USER` role account).
2. To get admin access, update the `role` column directly in the database:
   ```sql
   UPDATE app_user SET role = 'ADMIN' WHERE username = 'your-username';
   ```
3. Log in again — the new token will carry `ROLE_ADMIN` and unlock `/admin`.

---

## Useful commands

```bash
# Run backend tests
cd backend && mvnw test

# Run a single backend test class
cd backend && mvnw test -Dtest=JwtUtilTest

# Run frontend tests
cd frontend && npm test -- --watchAll=false

# Build backend JAR
cd backend && mvnw clean package -DskipTests

# Check Flyway migrations only
cd backend && mvnw flyway:info
```
