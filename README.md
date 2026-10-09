# PaySettle: ACH and Wire Settlement Platform

A payment settlement service for the two main US rails, built as one possible target for moving settlement
off a mainframe:

- **ACH**: NACHA files are processed with **Spring Batch** and settled through **FedACH** in the Same Day and
  next-day windows.
- **Wires**: **ISO 20022 pacs.008** messages go through an explicit state machine and a transactional outbox
  to **Fedwire Funds**.

Both rails share **fraud scoring, OFAC-style sanctions screening, duplicate detection** and **hourly
statistics**, shown in a built-in React operations console. You don't need Datadog or Jaeger.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Batch 5.2 · Spring Security (API key or OAuth2/JWT) ·
PostgreSQL 16 + Flyway · Prowide ISO 20022 · ShedLock · Micrometer/Prometheus · React 19 + Vite + TypeScript ·
GCP (Cloud Run, Cloud SQL, Secret Manager, Cloud Scheduler, Cloud Build).

## Architecture

```
                     ┌──────────────────────── Cloud Run: paysettle ────────────────────────┐
 Channels /          │                                                                      │
 mainframe  ──NACHA──▶ AchController ─▶ AchIngestService ─(file dedup)─▶ Spring Batch        │
                     │                                                    achIngestJob      │
                     │     validateFile ─▶ processEntries (chunk 500) ─▶ finalizeFile        │
                     │                         │ duplicates · Same Day eligibility · fraud  │
                     │                         ▼                                            │
                     │                    ach_entries ──(window cutoff)──▶ achSettlementJob ─┼─▶ FedACH (simulated)
                     │                                                    balanced NACHA    │
 Channels   ─JSON or─▶ WireController ─▶ WireService                                        │
 mainframe  pacs.008 │   Idempotency-Key · UETR · near-duplicate · sanctions · fraud        │
                     │        │ state machine (optimistic locking)                         │
                     │        ▼                                                             │
                     │   wires + outbox (same transaction) ─▶ WireSettlementWorker ─────────┼─▶ Fedwire (simulated)
                     │                                       (ShedLock, retries, backoff)   │   pacs.002 ACSC/RJCT, IMAD
                     │                                                                      │
                     │   fraud_alerts · duplicate_events · payment_events (audit)           │
                     │   ObservabilityController ─▶ React console (summary, hourly, alerts) │
                     └──────────────────────────────────────────────────────────────────────┘
                                                │ Cloud SQL Postgres 16
```

The service is a **modular monolith** with packages `ach`, `wire`, `fraud`, `observability` and `common`.
It deploys as one unit, but the boundaries are clean enough to split ACH and wires into separate services
later. Each rail has its own tables, jobs and gateway interface.

## Industry standards used

| Area | Standard | Where |
|---|---|---|
| ACH file format | **Nacha Operating Rules**: 94-character records (1/5/6/7/8/9), blocking factor 10, entry hash, batch/file control totals, SEC codes (PPD, CCD, WEB, TEL, CTX…), transaction codes | `ach/nacha/NachaParser`, `NachaFileBuilder` |
| Routing numbers | **ABA check digit** (weights 3-7-1) | `common/RoutingNumbers` |
| ACH settlement | **FedACH** windows: Same Day 1/2/3 and next-day; the **$1M Same Day per-payment limit** | `application.yml`, `AchSettlementService` |
| Wire messages | **ISO 20022 pacs.008.001.08** (customer credit transfer) and **pacs.002.001.10** (status: ACSC/RJCT), the Fedwire Funds format since March 2025: UETR, `USABA` clearing ids, clearing system `FDW`, settlement `CLRG`, charge bearer `SLEV` | `wire/iso/Iso20022Mapper` |
| Network rejects | ISO 20022 external reason codes (`AC01` incorrect account, `AM04` insufficient funds…) | `SimulatedFedwireGateway` |
| Retries | **Idempotency-Key** HTTP header (IETF draft); 201 for a new wire, 200 for a replay, 422 when a key is reused with a different body | `WireService` |
| Errors | **RFC 9457** Problem Details with a stable `code` | `ApiExceptionHandler` |
| Sanctions | OFAC SDN-style name screening (normalized names, Jaro-Winkler ≥ 0.92) | `fraud/SanctionsScreener` |
| Data protection | Account numbers stored as **AES-256-GCM** ciphertext, an **HMAC-SHA256** fingerprint (for matching) and the last 4 digits only | `common/FieldCrypto` |
| Auth | **OAuth2 resource server** (JWT with a `roles` claim) or API keys; maker-checker roles OPERATOR and ANALYST | `security/` |
| Metrics | Micrometer → `/actuator/prometheus`; Kubernetes-style liveness and readiness probes | `RiskRecorder`, `application.yml` |

## How each rail works

### ACH (Spring Batch)

1. `POST /api/v1/ach/files` receives a NACHA file. **File-level duplicates** are rejected at once with 409.
   A file is a duplicate when it has the same SHA-256 content hash, or the same NACHA file ID (immediate
   origin + creation date/time + File ID Modifier).
2. `achIngestJob` runs on a **single-threaded queue**, so cross-file duplicate checks can't race each other.
   It has three steps:
   - `validateFile` parses the whole file and checks record structure, control totals, entry hash and
     routing check digits. If anything fails, the file is REJECTED and processing stops.
   - `processEntries` processes entries in chunks of 500 with a **restartable reader** (its position is
     saved in the ExecutionContext). For each entry it:
     - checks for duplicates: the trace number, then a match on receiver account + amount + effective date
       across files and within the same file;
     - checks Same Day eligibility: effective today, before the last same-day cutoff, and at most $1M;
     - computes the fraud score.
   - `finalizeFile` records the totals.
3. Entries end up ACCEPTED, HELD_FRAUD (waiting for an analyst), DUPLICATE or REJECTED.
4. `achSettlementJob` runs at each window cutoff (ET).
   - `originateWindow` builds a **balanced outbound NACHA file** from the accepted entries and hands it to
     the FedACH gateway.
   - `completeSettlement` marks the batch SETTLED at the window's settlement time.
   - Weekends are skipped. The in-app scheduler (ShedLock) and Cloud Scheduler both call the sweep.

### Wires (state machine + outbox)

```
RECEIVED → VALIDATED → HELD_FRAUD | HELD_SANCTIONS | HELD_DUPLICATE → RELEASED → SENT → SETTLED
                    ↘ REJECTED                      ↘ CANCELLED                    ↘ REJECTED (network)
```

- Wires come in as JSON (`POST /api/v1/wires`) or as raw **pacs.008 XML** (`POST /api/v1/wires/iso20022`),
  for example forwarded from the mainframe. Either way the service builds and stores the canonical pacs.008.
- Every transition is checked against an allowed-transition map and written with **optimistic locking**
  (a version column). Each transition also writes an audit row to `payment_events`.
- A released wire and its **outbox** row are written in one transaction. `WireSettlementWorker` relays
  outbox rows to Fedwire. ShedLock ensures that only one instance relays at a time.
  - Retries use exponential backoff, up to 8 attempts.
  - On success it stores the **pacs.002** and the **IMAD**.
  - This gives at-least-once delivery without dual-write problems. Fedwire deduplicates on the UETR.

### Fraud, sanctions and duplicates

| Rail | Rules (explainable scores, 0–100; REVIEW ≥ 50 → hold, BLOCK ≥ 85 → reject) |
|---|---|
| ACH | High or very high amount; WEB debit over the threshold; **receiver velocity** (more than 20 entries to one account per hour, a mule pattern); new receiver with a high amount; round amount; same-day high value |
| Wire | High or very high amount; new beneficiary; new beneficiary with a high amount; **debtor velocity**; outside business hours; round amount; self-transfer. Any **sanctions match** is held for compliance |

| Duplicate check | Result |
|---|---|
| ACH file: same content hash or NACHA file ID | File rejected (409) |
| ACH trace number already used | Entry marked DUPLICATE |
| ACH entry: same receiver account, amount and effective date | Entry marked DUPLICATE |
| Wire: same Idempotency-Key | Original wire returned; no new payment |
| Wire: UETR reused | Rejected (409) |
| Wire: same debtor, creditor and amount within 30 minutes | Held; an operator releases or cancels it |

Fraud and sanctions holds follow **maker-checker**:
- An operator submits; only an **ANALYST** can clear a hold (the payment is released) or confirm fraud
  (the payment is rejected).
- A note is required, and both actions are written to the audit trail.

## Operations console

React 19 + Vite, served by the same container at `/`. It has these tabs:
- **Overview**: today's KPIs (volume, value, open alerts, sanctions holds, duplicates, rejects, items in
  flight, outbox backlog) and **hourly charts for the last 24 hours**: payments, value, fraud alerts and
  duplicates per hour, ACH and wire side by side. Each chart can be shown as a table. Also the fraud rules
  that fired in the last 7 days.
- **Fraud alerts**: filter by status and rail; analysts clear or confirm.
- **Duplicates**: every duplicate event, grouped by check.
- **ACH**:
  - inbound files, including the Spring Batch step counts;
  - entries with their audit trail;
  - FedACH settlement batches, with buttons to run a window or sweep.
- **Wires**: list, a new-wire form with an Idempotency-Key, and a detail view with the pacs.008 and
  pacs.002 XML and the audit trail.

For production alerting, the same counters are exported at `/actuator/prometheus`:
`paysettle_payments_total`, `paysettle_fraud_alerts_total` and `paysettle_duplicates_total`, tagged by rail and
by status, fraud action or duplicate kind.

## Run locally

Prerequisites: Java 21, Node 20+ and Docker.

```bash
docker compose up -d                                   # Postgres 16 on port 5435
SIMULATOR_ENABLED=true ./gradlew :backend:bootRun      # API on http://localhost:8080
```

```bash
cd frontend && npm install && npm run dev               # console on http://localhost:5174
```

Sign in with API key `local-operator-key` (operator) or `local-analyst-key` (fraud analyst).

Then click **ACH → Generate demo traffic**. This creates:
- **ACH**: payroll and WEB files, including a mule-velocity pattern and resubmitted entries, plus one file
  sent twice.
- **Wires**: high-value wires, a sanctioned name, an AC01 reject, client retries and double submissions.

The API without the console:

```bash
curl -X POST localhost:8080/api/v1/ach/files -H "X-API-Key: local-operator-key" -F file=@samples/payroll-ppd.ach
```

```bash
curl -X POST localhost:8080/api/v1/wires/iso20022 -H "X-API-Key: local-operator-key" -H "Content-Type: application/xml" -H "Idempotency-Key: $(uuidgen)" --data-binary @samples/wire-pacs008.xml
```

```bash
curl localhost:8080/api/v1/observability/hourly -H "X-API-Key: local-analyst-key"
```

Tests: `./gradlew :backend:test`. They cover:
- the NACHA build → parse round trip and validation errors;
- ABA check digits;
- ISO 20022 pacs.008/pacs.002 mapping;
- fraud and sanctions rules.

## API

| Method and path | Role | Purpose |
|---|---|---|
| `POST /api/v1/ach/files` (multipart `file` or `text/plain`) | OPERATOR | Receive a NACHA file; 202 with the Spring Batch job id, or 409 for a duplicate file |
| `GET /api/v1/ach/files`, `/files/{id}`, `/entries?fileId&status`, `/entries/{id}` | any | Files (with job and step status), entries, audit trail |
| `GET /api/v1/ach/settlement/batches` | any | FedACH settlement batches |
| `POST /api/v1/ach/settlement/windows/{window}/run`, `/sweep` | OPERATOR | Run a window now, or run every window that is due (Cloud Scheduler target) |
| `POST /api/v1/wires` (JSON) or `/wires/iso20022` (pacs.008 XML), header `Idempotency-Key` | OPERATOR | Submit a wire |
| `GET /api/v1/wires?status`, `/wires/{id}`, `/wires/{id}/pacs008` | any | Wires, history, ISO 20022 XML |
| `POST /api/v1/wires/{id}/release`, `/cancel` | OPERATOR | Release a possible duplicate, or cancel |
| `GET /api/v1/fraud-alerts?status&rail` · `POST /{id}/clear`, `/{id}/confirm` | any · ANALYST | Fraud and sanctions alerts (maker-checker) |
| `GET /api/v1/observability/summary`, `/hourly?hours=24`, `/duplicates`, `/fraud-rules` | any | Observability data |

## Deploy to Google Cloud

| Piece | GCP service |
|---|---|
| API + console (one container) | **Cloud Run**: one warm instance with always-on CPU, so the outbox relay and the settlement scheduler keep running; up to 3 instances, coordinated with ShedLock |
| Database | **Cloud SQL** Postgres 16, point-in-time recovery, connected through the Cloud SQL Java connector |
| Keys (DB password, API keys, AES data key, HMAC key) | **Secret Manager**, generated at setup and never printed |
| Settlement sweep backstop | **Cloud Scheduler**, every 5 minutes on business days (ET) |
| Image | **Cloud Build** + **Artifact Registry** |

```bash
gcloud auth login
PROJECT_ID=<your-project> ./deploy/gcp/01-setup.sh    # APIs, Cloud SQL, secrets, service account (~10 min)
PROJECT_ID=<your-project> ./deploy/gcp/02-build.sh    # build the container
PROJECT_ID=<your-project> ./deploy/gcp/03-deploy.sh   # Cloud Run + Cloud Scheduler
./deploy/gcp/api-key.sh operator                      # copies the key to the clipboard for the console
./deploy/gcp/status.sh
```

- **SSO:** set `JWT_ISSUER_URI` in `deploy/gcp/config.env` (Okta, Entra ID, Ping or Google) to require
  bearer tokens instead of API keys. Tokens need a `roles` claim with `OPERATOR` and/or `ANALYST`.
- **Cost:** about $45–60 a month while running (one always-on Cloud Run vCPU plus `db-f1-micro`). Between
  demos, run `./deploy/gcp/pause.sh` (Cloud Run scales to zero, the database stops, data is kept) and
  `./deploy/gcp/resume.sh` to start again. `./deploy/gcp/teardown.sh` deletes everything.

## Production gaps (what I'd add next)

- **Real network connectivity.** Connect to FedACH through FedLine Advantage/Direct and to Fedwire through
  FedLine Direct, implementing the `FedAchGateway` and `FedwireGateway` interfaces. Add MQ for the
  mainframe, for example IBM MQ with a strangler-fig adapter that forwards COBOL-originated files and
  messages.
- **ACH returns and NOCs.** Process the R01–R85 return codes and COR entries from FedACH, the return
  windows (2 banking days, 60 days for unauthorized consumer debits) and **return-rate monitoring**
  (Nacha's 0.5% unauthorized threshold).
- **Federal Reserve holiday calendar.** Today only weekends are skipped.
- **Ledger posting and reconciliation.** Post double-entry GL entries and reconcile against the Fed
  account statement (camt.052/053) and the FedACH settlement reports.
- **Fraud.** Add an ML model next to the rules (for example Vertex AI), consortium data and a case
  management tool. Screen against the real OFAC SDN list, refreshed daily, with fuzzy matching on aliases
  and addresses.
- **Resilience.** Use Cloud SQL HA with read replicas and a dead-letter queue for the outbox. Partition
  `ach_entries` by effective date.
- **Security.** Put Cloud Armor and IAP or a private ingress in front of the service, and move the
  encryption keys to Cloud KMS with envelope encryption and key rotation.

## Interview talking points

1. **Why Spring Batch for ACH?** ACH is file- and window-based. Spring Batch provides chunked,
   **restartable** processing: if the job dies on entry 40,000 of 100,000, it resumes from there. It also
   provides step-level metrics and a job repository that operations can audit. A REJECTED exit status ends
   a bad file cleanly.
2. **Why a state machine and outbox for wires?** Wires are individual, real-time and irrevocable once
   settled. An explicit transition map, optimistic locking and a transactional outbox mean a wire can never
   be both held and sent. It is never sent twice by the service, and Fedwire deduplicates on the UETR.
3. **Duplicates at every layer.** Duplicates are caught at the file, trace number, business match and
   client retry (Idempotency-Key) levels, and every one is recorded as an event, so the hourly chart shows
   them.
4. **Explainable fraud.** Every hold lists the rules that fired and the score. Analysts and regulators can
   see why, and the rules can be tuned from the "rules fired" table.
5. **Mainframe modernization path.** Run in parallel first: shadow-process the mainframe's NACHA files
   and compare the outputs. Then cut over one window or one SEC code at a time, keeping NACHA and ISO 20022
   as the contract, so the mainframe and this service exchange the same artifacts.

## Project layout

```
backend/    Spring Boot service: ach (NACHA, Spring Batch, FedACH settlement), wire (ISO 20022, state machine,
            outbox, Fedwire), fraud (rules, sanctions, alerts), observability, security; Flyway migrations
frontend/   React operations console
samples/    Sample NACHA file and pacs.008 / pacs.002 messages
deploy/gcp/ Setup, build, deploy, status, pause/resume and teardown scripts
```
