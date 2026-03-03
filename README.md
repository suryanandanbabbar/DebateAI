# DebateAI

## Secure API Key Setup

This project reads LLM credentials from environment variables and never from hardcoded values.

### 1) Local development with `.env`

Create a local `.env` file in the project root:

```bash
cp .env.example .env
```

Fill in your keys in `.env`:

```dotenv
OPENAI_API_KEY=your_openai_key_here
ANTHROPIC_API_KEY=your_anthropic_key_here
```

`.env` is ignored by Git and must never be committed.

### 2) Environment variables used by Spring Boot

`application.yml` references these variables:

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`

### 3) Export variables manually (Linux/Mac)

```bash
export OPENAI_API_KEY="your_openai_key_here"
export ANTHROPIC_API_KEY="your_anthropic_key_here"
```

### 4) Run locally

If you use shell exports:

```bash
./mvnw spring-boot:run
```

If you use `.env`, load it before running:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

### 5) CI/CD and container environments

Set keys only in secure secret stores:

- CI: GitHub Actions/GitLab/Jenkins encrypted secrets
- Containers: runtime environment variables (`docker run -e ...`) or orchestration secrets (Kubernetes Secrets, ECS task secrets)

Never place API keys in source code, committed config files, or Docker images.
