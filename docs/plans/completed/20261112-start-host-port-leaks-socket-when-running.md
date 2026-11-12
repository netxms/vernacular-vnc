# start(host, port) on a running client leaks its socket

Backlog item: `docs/backlog/start-host-port-leaks-socket-when-running.md`

## Overview
`VernacularClient.start(String, int)` connects a `Socket` and then calls `start(Socket)`. On a running client that call throws `IllegalStateException`, and the socket it just opened is never closed: the server holds a connection with no handshake until it times out. Found by the revmux review of the 1.20 work; it predates it.

## Decisions
- **Close the socket when `start(Socket)` throws, instead of checking `running` before connecting.** A pre-check races with another thread's `start()` between the check and the connect, and would still need the close to be correct. `start(String, int)` catches `IllegalStateException` from `start(Socket)`, closes the socket it opened, and rethrows. `start(Socket)` is unchanged: the caller owns a socket it passes in, and the javadoc already says the call throws on a running client.
- **No version bump.** `pom.xml` is already 1.20 and 1.20 has no tag yet, so the fix lands in 1.20.

## Implementation Steps

### Task 1: Close the socket start(host, port) opened when the client is already running
Files: `vernacular-vnc/src/main/java/com/shinyhut/vernacular/client/VernacularClient.java`, `vernacular-vnc/src/test/groovy/com/shinyhut/vernacular/client/VernacularClientTest.groovy`, `docs/backlog/start-host-port-leaks-socket-when-running.md`.

- [x] `start(String, int)` closes its socket when `start(Socket)` throws `IllegalStateException`, then rethrows it. The running session is not touched.
- [x] Spock spec: with the client running on the fixture server, `start('127.0.0.1', port)` against a local `ServerSocket` throws `IllegalStateException`; the connection the `ServerSocket` accepted reads EOF (bounded by a socket timeout, no sleeps); the original session is still running and no error was reported.
- [x] Delete `docs/backlog/start-host-port-leaks-socket-when-running.md`.
- [x] `mvn -f vernacular-vnc/pom.xml package` passes.
- ➕ A failure to close that socket is added to the rethrown `IllegalStateException` as suppressed, so the caller still gets the original exception (Codex's plan-review note).
- ➕ Against the old code the spec fails on the 5 s read timeout of the accepted connection.

### Task 2: Close out
- [x] Move this plan to `docs/plans/completed/`.

## Tandem

| Task | Writer | Reviewer |
|---|---|---|
| 1 | claude | codex |
| 2 | claude | codex |

## Outcome
- `mvn -f vernacular-vnc/pom.xml package` green, 164 tests, sources and javadoc jars.
- The other backlog item, the security-type count read as a signed byte, was dropped by the maintainer before the plan was written as defensive code, and its file deleted.
- revmux (comprehensive, 4/4 sources, round `01-initial`): no findings, no open questions, nothing pre-existing or immaterial.
