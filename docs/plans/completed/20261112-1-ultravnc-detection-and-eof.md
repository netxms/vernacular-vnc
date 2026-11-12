# UltraVNC Detection and Connection Lifecycle (1.20)

## Overview
1.19 sends `SetServerInput` (type 9) to any server the caller asks it to, so a non-UltraVNC server can drop the connection. NetXMS wants to switch blanking on with a server-wide option, and that is only safe if the library recognises UltraVNC and sends nothing to other servers.

1.20 adds that detection. It also fixes the client lifecycle so NetXMS can tell reliably when a session ends:
- a clean server EOF is reported (backlog item `server-eof-not-reported`), for example after a server restart, when UltraVNC drops the viewer, or when the server process is killed;
- each connection produces exactly one terminal event;
- cleanup no longer depends on the error listener returning.

NetXMS's `docs/plans/20261112-2-ultravnc-screen-blanking.md` (netxms repo) depends on this release.

## Decisions
- **Detect UltraVNC by type 17 in the security-type list offered under RFB 3.7/3.8.**
  - UltraVNC appends `rfbUltraVNC` (17) to its first offer only to identify itself. Source: `winvnc/winvnc/vncclient.cpp`, comment "send the UltraVNC auth type to identify ourselves as an UltraVNC Server, but only initially". UltraVNC's own viewer gives 17 top priority (`vncviewer/ClientConnection.cpp`). Our noVNC fork uses the same rule.
  - This reverses the 1.19 plan's "UltraVNC can't be reliably identified", which was never checked against the server source.
  - Take the flag from the raw offered list before the auth type is resolved, so it doesn't depend on which type gets selected. Never select 17 as an auth method: UltraVNC uses it to negotiate Ultra-specific auth.
  - RFB 3.3 sends a single server-chosen type and no list, so it is never detected. Neither is a server that doesn't offer 17. Both fail safe: nothing is sent.
- **Detection means "understands type 9", not "will blank".** UltraVNC ignores the message for view-only connections, lets only the viewer that blocked input unblock it, and blanks the monitor only if its own settings allow. The library never confirms blanking. The javadoc says so.
- **Send rule: send only while the connection is live and the server is detected.** It covers both send paths:
  - The bootstrap send in `start(Socket)` stays where it is: after `SetEncodings`, before the handler threads start, so before the first `FramebufferUpdateRequest`. Do not move it to an async path.
  - On an undetected server, both the bootstrap send and `setBlankRemoteScreen(boolean)` are silent no-ops, not exceptions. Callers reach them from config without knowing the server type.
- **Public API:** `VernacularClient.isServerInputSupported()` is valid once `start()` returns. It is per connection: a reused client that reconnects to an undetected server reports false. NetXMS uses it to enable its toggle.
- **First terminal event wins.** For each connection exactly one of these ends it, and only that one counts:
  - local `stop()` (never reported);
  - server EOF;
  - read or write failure;
  - startup failure.

  Rules that follow from it:
  - Simultaneous EOF and write failure produce one notification.
  - EOF or write failures after a local `stop()` are silent.
  - A write that fails while the connection is still active is reported, and it ends the connection.
  - A failure before a session exists is still reported. Example: connection refused, where `start(host, port)` builds the socket before `running` is set. So no blanket `!running` guard in `handleError`.
- **Make the connection terminal and clean it up before notifying.**
  - Today `handleError` calls the error listener first and `stop()` second. The viewer opens a modal dialog in that callback, so the client keeps running until the dialog is dismissed, and a listener that throws skips `stop()`.
  - New order: the connection becomes terminal first, so `isRunning()` is false inside the callback. Cleanup happens even if the listener blocks or throws. The notification runs outside any lifecycle lock.
  - A handler thread never joins itself. Today a stop called from the event-loop thread waits a full second in `join(1000)` on itself, and EOF would make that the normal way a session ends.
  - A late callback from an old session never stops a replacement session on a reused client (the viewer reuses its client).
- **No worker starts after termination.**
  - `start(Socket)` starts the server handler before the client handler, so an immediate EOF can end the connection before the second start.
  - `ClientEventHandler.start()` sets its own `running` unconditionally, so it would launch a framebuffer thread after shutdown. That must not happen.
- **Clean server EOF goes through the existing error path as a new `VncException` subclass (connection closed by server).**
  - A separate disconnect listener was ruled out: new API with one consumer. NetXMS already keeps the error listener's exception and logs it.
  - The viewer showing an error dialog when the remote side closes is intended.
- **Viewer app:** the error listener moves onto the Swing EDT (`SwingUtilities.invokeLater`); today it opens dialogs and resets widgets from the server thread. The blank checkbox needs no change, since its calls turn into no-ops on undetected servers.
- **Version 1.20** goes in both poms and the README snippets, in the first task, so a local `mvn install` doesn't overwrite the released 1.19.

## Implementation Steps

### Task 1: Connection lifecycle and server EOF
Layers: client lifecycle, server/client event handlers, exceptions, viewer app, test fixture, poms/README.

- [x] A clean server EOF on a live connection is reported once as the new connection-closed exception, and the client ends up stopped.
- [x] The first-terminal-event rule, terminal-before-notify, cleanup independent of the listener, no worker after termination, and stale-callback isolation all hold as specified in Decisions.
- [x] The viewer's error listener runs on the EDT.
- [x] Delete `docs/backlog/server-eof-not-reported.md`.
- [x] Version 1.20 in `vernacular-vnc/pom.xml`, `viewer/pom.xml` and both README snippets.
- [x] Test fixture: in `VernacularClientTest`, replace the `ChunkedInputStream` server, which hits EOF right after ServerInit, with a stream that blocks after ServerInit until the test releases EOF or closes it. Use latches, not delays.
- [x] New Spock specs, each asserting real thread exit and not only `isRunning()`:
  - immediate EOF after ServerInit;
  - EOF on a live session: one report, `isRunning()` false inside the callback;
  - a listener that blocks or throws still gets cleanup;
  - local stop, then EOF: silent;
  - blocked write, local stop, write throws: silent;
  - active write failure: reported and stopped;
  - EOF and write failure at the same time: one report;
  - connection refused: reported;
  - an old session's callback does not stop a reconnected session.
- [x] `mvn -f vernacular-vnc/pom.xml package` passes. It is `package` rather than `test` so the javadoc jar is also checked.
- ➕ No worker after termination is enforced one level up rather than inside `ClientEventHandler.start()`: a per-connection object starts both handlers under its own lock only if the connection is not yet terminal, and its shutdown reads the handlers under the same lock. A terminal event during startup therefore leaves both handlers unstarted.
- ➕ `start(host, port)` reports a refused connection without calling `stop()`. Before, it also stopped whatever session the client was running.
- ➕ The handler threads are named `vernacular-server-events` and `vernacular-framebuffer-requests`; the specs find them by name to assert real thread exit.
- ➕ The viewer uses the already imported `EventQueue.invokeLater`, which is what `SwingUtilities.invokeLater` delegates to.
- ➕ An extra spec covers a second stale-callback path: a write blocked on the old connection fails after the client has reconnected.
- ⚠️ Against the 1.19 code, the "simultaneous EOF and write failure" spec, "local stop, then EOF", "active write failure" and "connection refused" specs pass too (1.19 never reported EOF and already checked `running` in the handlers); the other seven new specs fail there.

- ➕ revmux (comprehensive, 4/4 sources) found that `Connection.close()` joined the handlers before closing the transport, so the reader blocked in `read()` survived the 1 s join: `stop()` and write-failure paths stalled, and a write-failure listener could run with the reader still alive. Fixed in this task's commit: the session and socket close first, then the handlers are joined. Two specs bound `stop()` and a write-failure report to under the join timeout; a thread-liveness check alone did not fail on the old order.

### Task 2: UltraVNC detection and gated SetServerInput
Layers: handshaking, session, client API.

- [x] Under RFB 3.7/3.8, `SecurityTypeNegotiator` records on `VncSession` whether the offered list contains 17. The default is false, and RFB 3.3 never sets it.
- [x] Add `VernacularClient.isServerInputSupported()`. Both send paths follow the send rule.
- [x] Javadoc on `VernacularConfig.setBlankRemoteScreen`, `VernacularClient.setBlankRemoteScreen` and `isServerInputSupported` states the detection rule, that undetected servers are ignored, and the server-side limitations. Remove the "may close the connection" wording.
- [x] Spock specs:
  - 3.7 and 3.8 with `[17, 2]` and `[2, 17]` are detected.
  - A list without 17, and RFB 3.3, send no type 9 even when the config flag is on.
  - On a detected server, the wire order is `SetEncodings` → type 9 → first `FramebufferUpdateRequest`.
  - The runtime toggle is a no-op on an undetected server and after `stop()`.
  - A reused client that reconnects from UltraVNC to an undetected server resets the flag.
- [x] `mvn -f vernacular-vnc/pom.xml package` passes (161 tests, sources and javadoc jars).
- ➕ `isServerInputSupported()` reports false before connecting and after termination; it reports support for the live connection only. Both blanking sends check that connection's terminal state and detection flag. Runtime sends retain the checked connection so a reconnect cannot redirect a request to an unchecked replacement.
- ➕ The lifecycle write-failure specs now use mouse movement so they still exercise writes on the default undetected fixture. Blanking specs explicitly offer type 17; the fixture supports RFB 3.3, 3.7 and 3.8. Output call counts verify ignored toggles never attempt a write.

## Post-Completion
- Manual checks with the viewer app:
  - Windows UltraVNC with "Enable Blank Monitor on Viewer Request": the monitor blanks on connect, the checkbox toggles it, and disconnecting restores it.
  - A non-UltraVNC server (TightVNC or macOS Screen Sharing) with the checkbox on: the session stays up.
  - Killing the server process: the viewer shows one disconnect dialog and resets.
- Release 1.20 to Maven Central (maintainer). After that, NetXMS plan 2 can bump its dependency.

## Tandem

| Task | Writer | Reviewer |
|---|---|---|
| 1 | claude | codex |
| 2 | codex | claude |

## Outcome
- Library build: `mvn -f vernacular-vnc/pom.xml package` green, 163 tests, sources and javadoc jars.
- revmux triage, agreed with Codex: the close-order finding was fixed (above); "start(host, port) on a running client leaks its socket" is pre-existing and went to `docs/backlog/start-host-port-leaks-socket-when-running.md`; "handler stop() swallows InterruptedException" (pre-existing) and "a replacement connection can start before the old notification is delivered" (immaterial, by design: reconnecting from the listener is supported) were skipped.
