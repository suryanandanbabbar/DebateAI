# Multi-Agent AI Debate System

## Overview
Multi-Agent AI Debate System is a Spring Boot application that orchestrates multiple AI agents to evaluate a decision topic and produce a structured, decisive outcome.

Instead of a single-model answer, the system runs parallel perspective-based reasoning:
- Optimist Agent
- Skeptic Agent
- Risk Analyst Agent
- Moderator Agent (decisive judge)

The moderator synthesizes arguments, validates option consistency, and returns one clear winner for the given topic.

## Architecture
### Core Components
- `DebateController`: REST entry point for `/debate`.
- `DebateService`: orchestration layer, parallel agent execution, moderation pipeline, and debate-level validation.
- Agents:
  - `OptimistAgent`: benefit-focused argument
  - `SkepticAgent`: trade-off and downside analysis
  - `RiskAnalystAgent`: operational/technical risk perspective
  - `ModeratorAgent`: decisive synthesis and winner selection
- `LLMClient` interface: provider abstraction.
- Provider implementations:
  - `OpenAIClient`
  - `AnthropicClient`
  - `GeminiClient`
- `LLMClientFactory` and provider switch logic: provider resolution without coupling agents to a specific API.
- Moderator validation logic:
  - option extraction from topic
  - winner validation against extracted options
  - invalid winner rejection with explicit exception

### Flow Diagram
```text
Client Request
      ↓
DebateService
      ↓
┌───────────────┬───────────────┬───────────────┐
Optimist      Skeptic       Risk Analyst
      ↓
Moderator (Decisive Judge)
      ↓
Structured Response
```

## Features
- Multi-agent reasoning with parallel execution
- Deterministic winner selection and validation
- Provider abstraction for OpenAI, Anthropic, and Gemini
- Retry handling and rate-limit-aware operation
- Strict option extraction and winner validation
- Structured response contract for success and abort states
- Mock development mode for local testing without paid API usage

## Tech Stack
- Java 21
- Spring Boot 3.x
- Spring Web (WebClient)
- CompletableFuture for parallel agent execution
- OpenAI API
- Anthropic API
- Google Gemini API

## Setup
### 1) Clone the repository
```bash
git clone https://github.com/suryanandanbabbar/DebateAI
cd DebateAI
```

### 2) Create `.env`
```bash
cp .env.example .env
```

Example `.env`:
```dotenv
OPENAI_API_KEY=your_openai_key_here
ANTHROPIC_API_KEY=your_anthropic_key_here
GEMINI_API_KEY=your_key_here
```

### 3) Ensure `.env` is ignored by Git
Add this to `.gitignore` if not already present:
```gitignore
.env
```

### 4) Run the application
```bash
mvn clean install
mvn spring-boot:run
```

Optional (Linux/Mac) if you want shell exports instead of `.env` import:
```bash
export OPENAI_API_KEY="your_openai_key_here"
export ANTHROPIC_API_KEY="your_anthropic_key_here"
export GEMINI_API_KEY="your_gemini_key_here"
```

## API Usage
### Request
```bash
curl -X POST http://localhost:8080/debate \
-H "Content-Type: application/json" \
-d '{"topic":"Should I use ChatGPT or Claude for coding?"}'
```

### Example Response
```json
{
  "topic": "Should I use ChatGPT or Claude for coding?",
  "optimist": "- ChatGPT has broad ecosystem support and strong coding assistance coverage.\n- Faster integration with common developer workflows.",
  "skeptic": "- Output quality may vary across edge cases.\n- Requires strict prompt discipline and verification.",
  "risk": "- Risk of over-reliance without test validation.\n- Data handling policy review is required before production usage.",
  "winner": "ChatGPT",
  "decisionReasoning": "ChatGPT is the winner.\n- Better tooling compatibility for day-to-day coding workflows.\n- Stronger practical productivity impact based on argument quality.\n- Lower integration friction for immediate adoption.",
  "confidence": 0.82
}
```

Aborted debate response example:
```json
{
  "topic": "Should I use ChatGPT or Claude for coding?",
  "error": "Debate failed due to unavailable agent outputs"
}
```

## Rate Limits
Gemini free-tier limits can be restrictive for multi-agent orchestration. A practical planning baseline is:
- about `5 RPM` (requests per minute)
- about `20 RPD` (requests per day)

For low-quota environments:
- prefer sequential or reduced-concurrency execution
- reduce retries during peak throttling windows
- enable mock mode for local development and CI

## Development Mode
Mock mode is intended for development when API keys are unavailable or rate limits are constrained.

Enable mock mode with:
```properties
debate.mode=mock
```

Recommended usage:
- local development
- integration testing without external API dependencies
- CI pipelines where external calls are undesirable

## Error Handling
The system handles and reports these failure paths:
- All agents failed: debate aborts before moderation.
- Invalid topic format: malformed option extraction is rejected.
- Moderator validation failure: no valid single winner or invalid selected option.
- Provider/API failures: retries and provider-level exceptions handled at orchestration boundaries.
- Rate limit exhaustion: surfaced as provider failure after retry policy is exhausted.

## Design Philosophy
- Use debate, not single-pass generation, for higher-quality decisions.
- Preserve argument structure across optimistic, skeptical, and risk viewpoints.
- Enforce decisive judgment in moderation rather than neutral summaries.
- Validate output contracts to prevent invalid winners and malformed decisions.

## Future Improvements
- Weighted scoring framework for moderator decision quality
- Persistence layer for debates, winners, and historical confidence
- Web UI dashboard for interactive debate sessions
- Expanded observability: metrics, traces, and structured audit logs
