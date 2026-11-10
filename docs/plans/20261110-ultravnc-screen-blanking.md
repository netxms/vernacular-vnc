# UltraVNC Screen Blanking

## Overview
UltraVNC's viewer can ask the server to disable its local keyboard/mouse and blank its monitor. Add the same request to vernacular-vnc: an opt-in config flag that sends it once after connecting, plus a runtime toggle. The bundled Swing viewer gets a checkbox so it can be tested by hand. NetXMS (nxmc) is the real consumer. Released as 1.19.

## Protocol (verified against UltraVNC `main`)
- Client message `rfbSetServerInput`, type `9`, 4 bytes: `CARD8 type, CARD8 status, CARD16 pad` (`rfb/rfbproto.h`; the struct's "scale" comments are stale copy/paste). Status `1` disables server input, `0` restores it.
- Server side (`winvnc/winvnc/vncclient.cpp`, case `rfbSetServerInput`): acted on only when the client has keyboard permission (view-only connections are ignored). Only the client that blocked input can unblock it (`m_bClientHasBlockedInput`); the server restores on that client's disconnect.
- Monitor blanking happens only if the server has "Enable Blank Monitor on Viewer Request" on and "BlankInputsOnly" off; otherwise only local input is disabled. The server sends no confirmation.
- No pseudo-encoding or first framebuffer update is required. UltraVNC's own viewer sends it only from its menu/toolbar, with no startup timing rule.

## Decisions
- **Opt-in, no auto-detection.** Type 9 is non-standard, and other servers may drop the connection on an unknown client message. UltraVNC can't be reliably identified from the handshake. Default `false` means no type-9 message at all, not even an "enable" one.
- **Do not advertise `rfbEncodingServerState`.** If advertised, the server would send `rfbServerState` (0xAD), and `ServerEventHandler` throws `UnknownMessageTypeException` on unknown types. Without it the server never sends one.
- **Initial send from `VernacularClient.start(Socket)`, not `Initializer`.** It goes after both handlers are constructed and before either starts: after `SetEncodings`, before the first `FramebufferUpdateRequest`. It uses the same locked `ClientEventHandler.sendMessage` path as the runtime toggle, so `Initializer` stays standard RFB and there is a single send site. The existing `IOException` catch in `start()` covers only local write failures.
- **A server that rejects type 9 closes the socket, and the client does not notice.** The write succeeds into the TCP buffer. `ServerEventHandler` exits silently on EOF, `ClientEventHandler` waits forever for a framebuffer update, and `isRunning()` stays true. This is existing EOF behaviour, not specific to this feature, so it is out of scope (backlog item `server-eof-not-reported`).
- **The runtime toggle does not write back to config.** Config is the initial state for each `start()`; the runtime call affects only the current connection (same as `moveMouse`, `copyText`). It follows the `copyText` shape with no extra guards; the javadoc says to call it only on a running client.
- **Version goes to 1.19** in both poms and in the README dependency snippets. This way `mvn install` doesn't overwrite the released 1.18 in the local `~/.m2`.
- **Builds run under JDK 17.** Groovy 4.0.18 (gmavenplus test compile) fails on the default JDK 25 with "Unsupported class file major version 69", even on unmodified master. Upgrading Groovy is out of scope (backlog item `groovy-jdk25-test-build`).
- **Ruled out:** `rfbServerState` handling / state feedback, UltraVNC detection, and a README feature section (javadoc covers it).

## Tandem

| Task | Writer | Reviewer |
|------|--------|----------|
| 1 | claude | codex |
| 2 | codex | claude |

## Implementation Steps

### Task 1: SetServerInput message and client API
Layers: client library (`protocol/messages`, `client`), build version.

New message `SetServerInput(boolean disabled)` implementing `Encodable`. New `VernacularConfig.blankRemoteScreen` (default `false`), new `ClientEventHandler.setServerInputDisabled(boolean)`, new public `VernacularClient.setBlankRemoteScreen(boolean)`. The javadoc on the config flag and the runtime method carries the protocol caveats above: UltraVNC-only, other servers may disconnect, blanking depends on server settings and is unconfirmed, view-only is ignored, only the blocking viewer can unblock, the runtime call affects the current connection only, and it must be called only on a running client.

The startup spec drives `start(Socket)` with a Spock `Stub(Socket)`. The input is a canned server stream: `RFB 003.008`, one security type None, SecurityResult OK, and a ServerInit. Output goes to a `ByteArrayOutputStream`. Use a real `VernacularConfig`, because a mocked one returns 0 FPS and `ClientEventHandler` divides by FPS. Call `client.stop()` in cleanup.

- [ ] `SetServerInput` encodes `[9, 1, 0, 0]` / `[9, 0, 0, 0]` (Spock spec in the style of `KeyEventTest`)
- [ ] with `blankRemoteScreen` set, `start(Socket)` writes `[9, 1, 0, 0]` right after `SetEncodings`; with it unset, no type-9 message is written (Spock spec on `VernacularClient`)
- [ ] `VernacularClient.setBlankRemoteScreen` sends the message on a running client and routes `IOException` to `handleError`
- [ ] both poms and the README dependency snippets at 1.19
- [ ] `JAVA_HOME=$(mise where java@17) mvn -q -f vernacular-vnc/pom.xml package` passes (runs tests and javadoc)

### Task 2: Viewer checkbox
Layers: Swing test viewer.

Options-menu checkbox "Blank Remote Screen (UltraVNC)" after "Use Local Cursor", with a tooltip saying it disables the remote's local keyboard/mouse and blanks its monitor if the server allows it. Unlike the other Options items it stays enabled while connected, so `setMenuState()` leaves it alone. The checkbox shows the requested state, not the verified server state.

- [ ] toggling updates the config; when connected it also calls `client.setBlankRemoteScreen`
- [ ] `JAVA_HOME=$(mise where java@17) mvn -q -f vernacular-vnc/pom.xml install && JAVA_HOME=$(mise where java@17) mvn -q -f viewer/pom.xml package` builds

## Post-Completion
Manual verification against a Windows UltraVNC server with "Enable Blank Monitor on Viewer Request" on:
- connect with the checkbox ticked → monitor blanks, local input disabled
- untick while connected → monitor and local input restored
- tick, then disconnect → server restores on its own
- (optional) connect to a non-UltraVNC server with the checkbox ticked → the server drops the connection and the viewer stalls with no error. This is the existing EOF behaviour, see `server-eof-not-reported`.

After release, nxmc can expose the flag/toggle in its VNC viewer.
