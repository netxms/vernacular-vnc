---
worth: yes
where: vernacular-vnc/src/main/java/com/shinyhut/vernacular/client/ServerEventHandler.java
added: 2026-11-10
---
# server closing the connection is not reported and the client stays running

When the server closes the socket cleanly, `ServerEventHandler`'s event loop exits on `in.read() == -1` and never calls `errorHandler`. Only a thrown `IOException` gets reported. `ClientEventHandler` then stays blocked in `VncSession.waitForFramebufferUpdate()` forever, and `VernacularClient.isRunning()` stays true. The error listener never fires, so the viewer and nxmc never reset; the session just freezes.

Found while planning UltraVNC screen blanking (`docs/plans/20261110-ultravnc-screen-blanking.md`): a non-UltraVNC server that rejects the type-9 message by disconnecting hits exactly this path. It applies to any server-side disconnect, though, not just that one.
