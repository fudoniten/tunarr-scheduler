# Tunarr Scheduler Service

This repository contains the foundations for a Clojure service that automates
programming for Tunarr channels. The long-term vision is to pull media metadata
from Jellyfin (or Tunarr itself), categorise the catalogue with the help of a
Large Language Model, orchestrate weekly schedules per channel, and generate
custom bumpers complete with text-to-speech narration.

## Features

* **Media ingestion** – pluggable catalog component prepared for Jellyfin or
  Tunarr data sources.
* **LLM abstraction** – a provider-agnostic interface for Ollama, OpenAI, or
  other Large Language Models.
* **Scheduling engine** – skeleton logic that will evolve to build balanced,
  seasonal programming blocks.
* **Bumper generation** – stubs for script generation and TTS synthesis.
* **HTTP API** – basic endpoints to trigger retagging, scheduling, and bumper
  creation.
* **Jellyfin tag sync** – push curated tags from the catalog to Jellyfin for
  use with ErsatzTV smart collections (see [JELLYFIN_SYNC.md](JELLYFIN_SYNC.md)).
* **Nix & Docker ready** – includes a development shell for NixOS and a Docker
  image suitable for Kubernetes deployments.

## Project Layout

```
├── resources/
│   ├── config.edn          # default service configuration
│   └── migratus.edn        # default database migration configuration
├── src/tunarr/scheduler/
│   ├── config.clj          # config loading helpers
│   ├── main.clj            # CLI entry point
│   ├── system.clj          # integrant system wiring
│   ├── http/               # API routes + server
│   ├── media/              # media catalogue integration
│   ├── scheduling/         # scheduling engine skeleton
│   ├── util/               # misc helpers
│   ├── llm.clj             # LLM abstraction
│   ├── tts.clj             # TTS abstraction
│   └── bumpers.clj         # bumper orchestration
└── test/                   # unit tests
```

## Getting Started

### Prerequisites

* [Clojure CLI tools](https://clojure.org/guides/install_clojure)
* Java 21+

### Running the service locally

```bash
clojure -M:run
```

Override configuration by pointing at a custom EDN file:

```bash
clojure -M:run --config my-config.edn
```

### Running the tests

```bash
clojure -M:test
```

### Development shell (Nix)

A [`flake.nix`](flake.nix) file is provided for NixOS hosts:

```bash
nix develop
clojure -M:run
```

### Docker image

Build and run the container image:

```bash
docker build -t tunarr-scheduler .
docker run -p 8080:8080 tunarr-scheduler
```

The container defaults to serving HTTP on port `8080` and reads configuration
from environment variables defined in `resources/config.edn`.

### Database migrations

Database migrations are managed with
[Migratus](https://github.com/yogthos/migratus). The default configuration lives
in `resources/migratus.edn` and assumes a PostgreSQL database reachable at
`jdbc:postgresql://localhost:5432/tunarr_scheduler`. Point the migratus runner
at an alternate configuration by setting the `MIGRATUS_CONFIG` environment
variable when invoking the `tunarr-scheduler-migratus` container or binary.

For example, to run migrations locally with the default config:

```bash
clojure -Sdeps '{:deps {migratus/migratus {:mvn/version "1.6.3"}}}' \
  -M -m migratus.core migrate resources/migratus.edn
```

Or, using a custom config file:

```bash
MIGRATUS_CONFIG=infra/prod-migratus.edn \
  tunarr-scheduler-migratus
```

#### LLM configuration

To use the OpenAI client set the following environment variables (or override
the configuration file):

* `LLM_PROVIDER=openai`
* `LLM_API_KEY=<your-openai-api-key>`
* `LLM_MODEL` (defaults to `gpt-4o`)
* `LLM_ENDPOINT` (defaults to `https://api.openai.com/v1`)

The service will call the Chat Completions API for media classification,
scheduling suggestions, and bumper script generation.

### Kubernetes deployment

The resulting image can be used in Kubernetes by defining a `Deployment` and
`Service`. Configure secrets for Jellyfin, Tunarr, LLM, and TTS endpoints as
needed. The service exposes the following HTTP endpoints:

* `GET /healthz` – readiness/liveness probe.
* `POST /api/media/retag`
* `POST /api/channels/{channel-id}/schedule`
* `POST /api/bumpers/up-next`

Payloads are JSON documents. The current implementation is a scaffold intended
for expansion.

## Next Steps

* Implement actual Jellyfin and Tunarr API integrations.
* Persist tagged media and schedules in a durable store (PostgreSQL, SQLite,
  etc.).
* Replace mock LLM/TTS logic with real providers.
* Enrich scheduling heuristics and add background jobs for periodic updates.
* Generate audio assets and integrate with Tunarr channel playout.

Contributions are welcome as the project grows.
