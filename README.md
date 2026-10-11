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

### FedACH connectivity (FedLine)

FedLine is not a public API. It is the Federal Reserve's private connection for banks with a Fed account:
Fed-supplied VPN devices and Fed-issued certificates, set up through the Fed's onboarding and certification
tests. Banks exchange ACH files over FedLine Direct with managed file transfer software such as IBM Sterling
Connect:Direct. The `FedAchGateway` interface hides this, and there are two implementations:

| Gateway | When | Behavior |
|---|---|---|
| `SimulatedFedAchGateway` | default (`FEDLINE_ENABLED=false`) | Checks the file the way the Fed would and acknowledges it at once |
| `FedLineAchGateway` (skeleton) | `FEDLINE_ENABLED=true` | Writes the file to `FEDLINE_OUTBOUND_DIR` (temporary name, then an atomic rename, so the transfer software never picks up a half-written file) and returns. The batch stays **TRANSMITTED** |

`FedLineAckPoller` reads the Fed's acknowledgements from `FEDLINE_ACK_DIR` and archives each one to
`FEDLINE_ARCHIVE_DIR`:
- **Accepted:** the batch becomes ACKNOWLEDGED and settles at the window's settlement time. A batch is
  never settled without an acknowledgement.
- **Rejected:** the batch becomes FAILED with the reason. Its entries return to ACCEPTED and go out in
  the next window.
- **Unknown or unreadable:** the file is set aside for operations to look at.
- **No acknowledgement after `ack-timeout-minutes`:** a warning is logged so operations can chase it
  before the settlement time.

The acknowledgement format in the skeleton is a placeholder (`FILE=`, `STATUS=`, `REFERENCE=`, `REASON=`).
The Fed specifies the real one during onboarding, and only `FedLineAckPoller.parse` changes for it. FedLine
needs persistent shared directories, so it runs on-premises or on GKE with a mounted volume, not on Cloud
Run. The demo deployment uses the simulator.

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
./scripts/dev.sh          # Postgres on 5435, API on http://localhost:8080, console on http://localhost:5174
./scripts/dev.sh status   # what is running
./scripts/dev.sh stop     # stop the API and console (docker compose down stops Postgres)
```

The script builds the backend first and turns on the demo traffic generator. Set `SKIP_BUILD=true` to start
the last build, or `SIMULATOR_ENABLED=false` to turn the generator off. Logs are in `.run/`.

For a steady stream of demo traffic (so the hourly charts fill in), run `./scripts/traffic.sh`. It does a
round every 2 minutes until Ctrl+C; set `ROUNDS` and `INTERVAL` to change that.

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

- **Real network connectivity.** Finish `FedLineAchGateway` with the bank's FedLine onboarding (file naming
  and the real acknowledgement format), and add a FedLine Direct implementation of `FedwireGateway` for
  wires. Add MQ for the
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
## Worked example: one ACH payment from customer to the Fed

One $1,250.00 payroll credit, followed through the whole flow.

| Party | Value |
|---|---|
| Customer (originator) | Acme Manufacturing, company ID `1412345678` |
| Employee (receiver) | Jane Doe, account at JPMorgan Chase (routing `021000021`) |
| Our bank (ODFI) | routing `123456780` (a valid-checksum test number) |
| Federal Reserve (FedACH) | routing `011000015` (example) |

Every NACHA record is exactly 94 characters. Trailing spaces may not show on screen.

### 1. File the customer sends to the bank (NACHA)

```
101 12345678014123456782610091430A094101PAYSETTLE BANK         ACME MANUFACTURING INC
5220ACME MFG                            1412345678PPDPAYROLL         261013   1123456780000001
622021000021123456789        0000125000EMP0042        JANE DOE                0123456780000001
822000000100021000020000000000000000001250001412345678                         123456780000001
9000001000001000000010002100002000000000000000000125000
```

| Record | Key fields |
|---|---|
| **1** File header | Destination ` 123456780` = our bank. Origin `1412345678` = Acme's company ID ("1" + EIN). Created 2026-10-09 14:30, File ID Modifier `A` |
| **5** Batch header | Service class `220` = credits only. SEC `PPD` = consumer payroll. Effective date `261013` (Tue, Oct 13; Mon, Oct 12 is Columbus Day, a Fed holiday). Settlement date (positions 76–78) is blank; the Fed fills it in. Originating bank = `12345678` |
| **6** Entry | Code `22` = checking credit. Receiving bank `02100002` + check digit `1`. Account `123456789`. Amount `0000125000` = $1,250.00. Trace `123456780000001` |
| **8** Batch control | 1 entry. Entry hash `0002100002` = sum of the receiving banks' 8-digit routing numbers. Credits $1,250.00 |
| **9** File control | Batch, block and entry counts, entry hash and totals. The file is then padded with lines of `9`s to a multiple of 10 records |

### 2. What the bank does before sending it on

1. **Validates the whole file:** control totals, entry hash, routing check digits, record format.
2. **Checks each entry:** duplicates, fraud scoring, sanctions, customer exposure limits, Same Day eligibility.
3. **Combines the entries that passed** with those from all other customers' files for the same FedACH window, and builds a new file.

### 3. File the bank sends to the Fed over FedLine (NACHA)

```
101 011000015 1234567802610091505C094101FEDERAL RESERVE BANK   PAYSETTLE BANK
5220ACME MFG                            1412345678PPDPAYROLL         261013   1123456780000037
622021000021123456789        0000125000EMP0042        JANE DOE                0123456780004812
822000000100021000020000000000000000001250001412345678                         123456780000037
9000001000001000000010002100002000000000000000000125000
```

What changed from the customer's file:

| Field | Customer → bank | Bank → Fed |
|---|---|---|
| Immediate destination | ` 123456780` (our bank) | ` 011000015` (the Fed) |
| Immediate origin | `1412345678` (Acme) | ` 123456780` (our bank's routing number) |
| Names | PAYSETTLE BANK / ACME MANUFACTURING INC | FEDERAL RESERVE BANK / PAYSETTLE BANK |
| Creation time, File ID Modifier | 14:30, `A` | 15:05, `C` (the bank's 3rd file to the Fed today) |
| Batch number | `0000001` | `0000037` (the bank numbers batches across all customers) |
| Trace number | `123456780000001` | `123456780004812` (assigned by the bank to be unique; the first 8 digits are always the originating bank's routing number) |
| File control | 1 batch | Totals for all customers' batches in the file (only one shown here) |

**What stays the same:** the company ID, SEC code, effective date, the employee's bank and account, the amount and the transaction code. The payment itself never changes, only how it's packaged and who it's from.

The bank's managed file transfer software sends this file to the Fed over FedLine Direct, using the bank's Fed-issued certificates. In this project, `FedLineAchGateway` hands the file to that software (see [FedACH connectivity](#fedach-connectivity-fedline)).

### 4. The Fed's acknowledgement (illustrative, not NACHA)

The Fed confirms that it received and accepted the file, or rejected it. The exact layout is defined in the Fed's FedACH specifications, which banks receive during FedLine onboarding. **This is an illustrative layout, not the real one:**

```
FEDACH FILE ACKNOWLEDGEMENT
SENDING DFI ......... 123456780  PAYSETTLE BANK
FILE CREATED ........ 2026-10-09 15:05   FILE ID MODIFIER: C
RECEIVED ............ 2026-10-09 15:06:12 ET
STATUS .............. ACCEPTED
BATCHES ............. 000001
ENTRIES/ADDENDA ..... 00000001
ENTRY HASH .......... 0002100002
TOTAL DEBITS ........ $0.00
TOTAL CREDITS ....... $1,250.00
```

The acknowledgement echoes the file's identity and control totals: sender, creation date and time, File ID Modifier, counts, entry hash and totals. The bank matches these against what it sent. A rejection gives the reason, for example a duplicate file or a control total mismatch.

### 5. What Chase receives from the Fed (NACHA again)

The Fed sorts entries by receiving bank and fills in the settlement date as a 3-digit day of the year:

```
5220ACME MFG                            1412345678PPDPAYROLL         261013286   1123456780000037
```

`286` is October 13, 2026. On that day the Fed credits Chase's Fed account and debits ours, and Chase posts $1,250.00 to Jane Doe's account.

## Worked example: one domestic wire over Fedwire Funds

Wires are different from ACH in three ways:
- **One payment per message**, not batches in a file.
- **Real-time gross settlement:** each wire settles individually, in seconds, and is final once settled.
- **ISO 20022 XML** instead of 94-character records. Fedwire Funds moved to ISO 20022 in March 2025.

| Party | Value |
|---|---|
| Customer (debtor) | Acme Manufacturing, account `4401229871` at our bank |
| Our bank | routing (ABA) `123456780` (a valid-checksum test number) |
| Beneficiary (creditor) | Blue Ridge Supply Co, account `7730015512` at JPMorgan Chase (ABA `021000021`) |
| Amount | $18,250.00 |

The XML below is trimmed to the important elements. The exact Fedwire field rules are in the Fedwire ISO 20022 implementation guide, which banks get during onboarding.

### 1. What the customer sends the bank

Corporate customers usually send an ISO 20022 **pain.001** (customer credit transfer initiation) over host-to-host, or enter the wire in online banking. Simplified:

```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
  <CstmrCdtTrfInitn>
    <GrpHdr><MsgId>ACME-20261009-0042</MsgId><CreDtTm>2026-10-09T10:14:00-04:00</CreDtTm>
      <NbOfTxs>1</NbOfTxs><InitgPty><Nm>Acme Manufacturing Inc</Nm></InitgPty></GrpHdr>
    <PmtInf>
      <PmtInfId>ACME-PAY-0042</PmtInfId><PmtMtd>TRF</PmtMtd><ReqdExctnDt><Dt>2026-10-09</Dt></ReqdExctnDt>
      <Dbtr><Nm>Acme Manufacturing Inc</Nm></Dbtr>
      <DbtrAcct><Id><Othr><Id>4401229871</Id></Othr></Id></DbtrAcct>
      <DbtrAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>123456780</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
      <CdtTrfTxInf>
        <PmtId><EndToEndId>INV-88231</EndToEndId></PmtId>
        <Amt><InstdAmt Ccy="USD">18250.00</InstdAmt></Amt>
        <CdtrAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
        <Cdtr><Nm>Blue Ridge Supply Co</Nm></Cdtr>
        <CdtrAcct><Id><Othr><Id>7730015512</Id></Othr></Id></CdtrAcct>
        <RmtInf><Ustrd>Invoice 88231</Ustrd></RmtInf>
      </CdtTrfTxInf>
    </PmtInf>
  </CstmrCdtTrfInitn>
</Document>
```

In this project the customer's request comes in as JSON on `POST /api/v1/wires` (with an `Idempotency-Key` header), or as a ready-made pacs.008 on `POST /api/v1/wires/iso20022`.

### 2. What the bank does before sending it on

1. **Validates:** routing numbers, amount, USD only, value date, Fedwire cutoff (18:00 ET for customer transfers).
2. **Checks for duplicates:** `Idempotency-Key` replay, UETR reuse, same debtor, creditor and amount within 30 minutes.
3. **Screens and scores:** sanctions screening of names (OFAC), and fraud scoring (amount, new beneficiary, velocity, outside business hours).
4. **Checks the money:** the customer's available balance and limits, then debits or reserves funds.
5. **Builds the pacs.008 and sends it.** Holds wait for an analyst (fraud or sanctions) or an operator (possible duplicate). A wire can't be recalled after it settles, so every check happens before release.

### 3. What the bank sends to Fedwire (pacs.008, over FedLine)

Changes from the customer's instruction: a **UETR** (unique end-to-end reference) is added, the bank becomes the **instructing agent**, Fedwire is the clearing system, and the settlement date and charge bearer are set. Simplified, without the business application header (head.001) that wraps every Fedwire message:

```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>20261009PSB1FT01000317</MsgId>
      <CreDtTm>2026-10-09T10:15:03-04:00</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <SttlmInf><SttlmMtd>CLRG</SttlmMtd><ClrSys><Cd>FDW</Cd></ClrSys></SttlmInf>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>20261009PSB1FT01000317</InstrId>
        <EndToEndId>INV-88231</EndToEndId>
        <UETR>8a562c67-ca16-48ba-b074-65581be6f001</UETR>
      </PmtId>
      <PmtTpInf><LclInstrm><Prtry>CTRC</Prtry></LclInstrm></PmtTpInf>   <!-- Fedwire: customer transfer -->
      <IntrBkSttlmAmt Ccy="USD">18250.00</IntrBkSttlmAmt>
      <IntrBkSttlmDt>2026-10-09</IntrBkSttlmDt>
      <ChrgBr>SLEV</ChrgBr>
      <InstgAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>123456780</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>
      <InstdAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></InstdAgt>
      <Dbtr><Nm>Acme Manufacturing Inc</Nm></Dbtr>
      <DbtrAcct><Id><Othr><Id>4401229871</Id></Othr></Id></DbtrAcct>
      <DbtrAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>123456780</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
      <CdtrAgt><FinInstnId><ClrSysMmbId><ClrSysId><Cd>USABA</Cd></ClrSysId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
      <Cdtr><Nm>Blue Ridge Supply Co</Nm></Cdtr>
      <CdtrAcct><Id><Othr><Id>7730015512</Id></Othr></Id></CdtrAcct>
      <RmtInf><Ustrd>Invoice 88231</Ustrd></RmtInf>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>
```

| Field | Customer instruction (pain.001) | Bank → Fedwire (pacs.008) |
|---|---|---|
| Message type | Customer initiation | Bank-to-bank customer credit transfer |
| Who is sending | Acme (initiating party) | Our bank (`InstgAgt` = `123456780`) |
| Who receives the message | Our bank | Chase (`InstdAgt` = `021000021`), through Fedwire (`ClrSys` = `FDW`) |
| Tracking | Acme's `EndToEndId` | Same `EndToEndId` **plus a UETR** that stays with the payment end to end |
| Settlement | Requested date | `IntrBkSttlmDt`, settlement method `CLRG`, charge bearer `SLEV` |

**What stays the same:** the debtor and creditor, their accounts and banks, the amount and the remittance information.

### 4. Fedwire's answer (pacs.002)

Fedwire settles the wire immediately by debiting our bank's Fed account and crediting Chase's. It then confirms with a status report. Fedwire also assigns its own references: the **IMAD** (input message accountability data: date + sender ID + sequence) and the **OMAD** (output message accountability data). Where these appear in the message is defined in the Fedwire ISO 20022 implementation guide. Simplified:

```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt>
    <GrpHdr><MsgId>FDWSTS20261009000981</MsgId><CreDtTm>2026-10-09T10:15:04-04:00</CreDtTm></GrpHdr>
    <OrgnlGrpInfAndSts><OrgnlMsgId>20261009PSB1FT01000317</OrgnlMsgId><OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId></OrgnlGrpInfAndSts>
    <TxInfAndSts>
      <OrgnlEndToEndId>INV-88231</OrgnlEndToEndId>
      <OrgnlUETR>8a562c67-ca16-48ba-b074-65581be6f001</OrgnlUETR>
      <TxSts>ACSC</TxSts>                                  <!-- accepted, settlement completed -->
      <!-- Rejected instead: <TxSts>RJCT</TxSts><StsRsnInf><Rsn><Cd>AC01</Cd></Rsn></StsRsnInf> (AC01 = incorrect account number) -->
    </TxInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
```

Unlike ACH, **the acknowledgement is ISO 20022 too**, and it's per payment. `ACSC` means the money has moved and the wire is final. In this project the simulated Fedwire returns exactly this message, with an IMAD such as `20261009PSB1FT01000317`. Account numbers ending `0000` produce an `AC01` reject, and amounts ending `.13` produce `AM04` (insufficient funds).

### 5. What Chase receives

Chase receives the same pacs.008 from Fedwire, with the Fed's references attached, and credits Blue Ridge Supply's account. The UETR lets all three banks and both customers trace the one payment.

**Interview summary:** "ACH is batched files that settle at set windows and can be returned. Fedwire is one ISO 20022 message per payment that settles immediately and is final. So every check (duplicates, sanctions, fraud, funds) happens before release, and the UETR ties the payment together end to end."