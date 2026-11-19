# Inbox pattern + AI healing demo

A Claude generated Spring Boot demo of the inbox pattern where a failed message is repaired by Claude and flagged for replay.

```
controller/   DemoController, DemoFixtures      REST API, one endpoint per demo beat
service/      InboxService                      puts data in the inbox, processes it, heals failures
              ReplayService                     replays READY_FOR_RETRY records
              ClaudeService                     the only thing that talks to Claude
              FileInbox                         saves records as JSON files
model/        InboxRecord, InboxStatus
```

- **InboxService** persists each message first, then validates and processes it. Failures are recorded with a specific error.
- **Healing** sends Claude the failed data and the stored error. Claude answers with only the fix (`{ field, value }` plus a one-line explanation) through structured outputs, so the reply always matches a JSON schema. The service applies that one change and sets `READY_FOR_RETRY`. If Claude would have to guess, it returns no fix and the record goes to `NEEDS_REVIEW`.
- **ReplayService** only touches `READY_FOR_RETRY`. It has no opinion about why anything failed.
- **Storage** is a folder of JSON files, one per message, so you can open a file before and after healing. No Docker, no database.

## Run it

Requires Java 17+, Maven, and an Anthropic API key.

```bash
export ANTHROPIC_API_KEY=sk-ant-...        # never commit this
mvn spring-boot:run
```

Open <http://localhost:8080> for the button page and watch the `demo-inbox/` folder next to it. The app starts without a key and the good/broken flow works, but healing then leaves the record as `FAIL` with a note saying the key is not set.

The model is set by `claude.model` in `application.yml` (default `claude-sonnet-5-5`); override with `CLAUDE_MODEL=...`. Behind a corporate proxy, make sure `api.anthropic.com` is reachable; the JVM's `https.proxyHost` / `https.proxyPort` settings are honoured.

**Warm-up before the demo:** the first request with a new response schema is slower, because the schema is compiled and then cached for about 24 hours. Send one broken message and run the healer once before you go on stage.

## Endpoints

| Beat | Endpoint | What it does |
|---|---|---|
| Good message | `POST /demo/messages/good` | Valid order, ends `COMPLETE` |
| Broken message | `POST /demo/messages/broken?defect=bad-date` | Healable defect, ends `FAIL`. Defects: `bad-date`, `bad-status`, `word-quantity` |
| Unhealable message | `POST /demo/messages/unhealable` | No `customerId`, so Claude should decline |
| Inspect | `GET /demo/messages`, `GET /demo/messages/{id}` | Current records, errors, heal notes |
| Heal | `POST /demo/heal`, `POST /demo/heal/{id}` | Ask Claude to fix every `FAIL` record, or one |
| Replay | `POST /demo/replay` | Replay `READY_FOR_RETRY` records only |
| Reset | `POST /demo/reset` | Clear the inbox |

## Suggested script

1. Explain the inbox pattern. Show `InboxService`, `FileInbox` and the validation rules. Hold back `ClaudeService`.
2. `POST /demo/messages/good`. Open the file: `COMPLETE`.
3. `POST /demo/messages/broken`. Open the file: `FAIL`, with a readable error.
4. `POST /demo/replay`. Nothing happens. The record is stuck because it is not flagged.
5. Reveal `ClaudeService`. `POST /demo/heal`. Show `originalData` against `data`, the heal note, and `READY_FOR_RETRY`.
6. `POST /demo/replay`. The record reaches `COMPLETE`.
7. `POST /demo/messages/unhealable`, then `POST /demo/heal`. It lands in `NEEDS_REVIEW` and replay never touches it.
8. Close on production considerations (below).

## Status flow

```
RECEIVED -> COMPLETE
         -> FAIL -> READY_FOR_RETRY -> COMPLETE
                                    -> FAIL
                 -> NEEDS_REVIEW
```

`RECEIVED` exists because the inbox pattern writes the message before processing it. If the process dies in between, the record is still there to pick up.

If the Claude call itself fails (no key, network, API error), the record stays `FAIL` and a note says why.

## Running replay on a timer

The demo drives heal and replay by hand. Set `demo.scheduler.enabled=true` to replay `READY_FOR_RETRY` records every `demo.scheduler.replay-interval-ms` (default 10 seconds).
