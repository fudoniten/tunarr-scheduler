# Tunarr Scheduler Service

This repository contains the foundations for a Clojure service that automates
programming for TV channels. The long-term vision is to pull media metadata
from Jellyfin, categorise the catalogue with the help of a Large Language Model,
orchestrate weekly schedules per channel, and generate custom bumpers complete
with text-to-speech narration.

## Features

* **Media ingestion** - pluggable catalog component for Jellyfin data sources.
* **LLM abstraction** - a provider-agnostic interface for Ollama, OpenAI, or
  other Large Language Models via TunaBrain.
* **Scheduling engine** - multi-level planning (seasonal, monthly, weekly) for
  balanced programming blocks.
* **Bumper generation** - stubs for script generation and TTS synthesis.
* **HTTP API** - endpoints to trigger retagging, scheduling, and bumper creation.
* **Pseudovision integration** - direct sync of tags and schedules to Pseudovision
  for IPTV streaming (see [PSEUDOVISION_SYNC.md](PSEUDOVISION_SYNC.md)).
* **Nix & Docker ready** - includes a development shell for NixOS and a Docker
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
│   ├── backends/           # backend clients (Pseudovision)
│   ├── scheduling/         # scheduling engine
│   ├── curation/           # LLM-based tagging and categorization
│   ├── channels/           # channel management
│   ├── util/               # misc helpers
│   ├── tunabrain.clj       # TunaBrain LLM client
│   ├── tts.clj             # TTS abstraction
│   └── bumpers.clj         # bumper orchestration
└── test/                   # unit tests
```

## Architecture

```
┌─────────────────────┐
│  tunarr-scheduler   │
│  - Jellyfin sync    │
│  - LLM categorize   │
│  - Tag management   │
└──────────┬──────────┘
           │
           │ Direct API
           ▼
┌─────────────────────┐
│   Pseudovision      │
│  - Tag storage      │
│  - Scheduling       │
│  - HLS streaming    │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│     Jellyfin        │
│  - Media server     │
└─────────────────────┘
```

## Getting Started

### Prerequisites

* [Clojure CLI tools](https://clojure.org/guides/install_clojure)
* Java 21+

### Running the service locally

```bash
clojure -M:run --config my-config.edn
```

Options:
- `--config PATH` - Path to configuration EDN file (can be specified multiple times)
- `--log-level LEVEL` - Set log level (trace, debug, info, warn, error)
- `--help` - Show usage information

Examples:

```bash
# Run with default config and debug logging
clojure -M:run --config resources/config.edn --log-level debug

# Run with multiple config files
clojure -M:run --config base-config.edn --config prod-overrides.edn
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
docker run -p 5545:5545 tunarr-scheduler
```

The container defaults to serving HTTP on port `5545` and reads configuration
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
clojure -Sdeps '{:deps {migratus/migratus {:mvn/version \"1.6.3\"}}}' -M -m migratus.core migrate resources/migratus.edn
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

#### TunaBrain configuration

TunaBrain is the LLM orchestration service that powers media categorization:

* `TUNABRAIN_ENDPOINT` (defaults to `http://localhost:5546`)

### Kubernetes deployment

The resulting image can be used in Kubernetes by defining a `Deployment` and
`Service`. Configure secrets for Jellyfin, Pseudovision, LLM, and TTS endpoints
as needed. The service exposes the following HTTP endpoints:

* `GET /healthz` - readiness/liveness probe.
* `POST /api/media/:library/sync-pseudovision-tags`
* `POST /api/media/:library/retag`
* `POST /api/media/tags/audit` - async LLM tag audit; deletes unsuitable tags
  unless `?dry-run=true`. Results are reported via `GET /api/jobs/:job-id`.
* `POST /api/media/tags/triage` - async LLM tag governance; applies
  keep/remove/rename decisions using per-tag usage counts and example titles.
  Supports `?dry-run=true` and `?target-limit=N`.
* `POST /api/channels/sync-pseudovision`
* `POST /api/channels/:channel-id/schedule`
* `POST /api/bumpers/up-next`

Payloads are JSON documents.

## Next Steps

* Implement LLM-driven scheduling agent for automated programming
* Add web UI for instruction editing and schedule preview
* Add episode tracking database schema
* Implement bumper generation with TTS
* Set up automated daily schedule regeneration

See [TODO.md](TODO.md) for the complete roadmap.

## Related Documentation

- [PSEUDOVISION_INTEGRATION.md](PSEUDOVISION_INTEGRATION.md) - Integration design
- [PSEUDOVISION_SYNC.md](PSEUDOVISION_SYNC.md) - Tag sync documentation
- [PSEUDOVISION_MIGRATION.md](PSEUDOVISION_MIGRATION.md) - One-time migration guide
- [SCHEDULING.md](SCHEDULING.md) - Scheduling system design

Contributions are welcome as the project grows.