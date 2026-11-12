package com.shinyhut.vernacular.client;

import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.protocol.handshaking.Handshaker;
import com.shinyhut.vernacular.protocol.initialization.Initializer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static java.util.stream.IntStream.range;

public class VernacularClient {

    private final Handshaker handshaker;
    private final Initializer initializer;

    private final VernacularConfig config;

    private final Object lifecycleLock = new Object();

    private volatile Connection connection;
    private volatile boolean running;

    /**
     * Creates a new VNC client using the specified configuration object
     *
     * @param config The VNC client configuration
     */
    public VernacularClient(VernacularConfig config) {
        this.config = config;
        this.handshaker = new Handshaker();
        this.initializer = new Initializer();
    }

    /**
     * Starts the VNC client by connecting to the specified remote host and port
     *
     * @param host Remote host to connect to
     * @param port Remote port to connect to
     * @throws IllegalStateException if the client is already running
     */
    public void start(String host, int port) {
        Socket socket;
        try {
            socket = new Socket(host, port);
        } catch (IOException e) {
            notifyErrorListeners(new UnexpectedVncException(e));
            return;
        }
        start(socket);
    }

    /**
     * Starts the VNC client by connecting to the specified socket
     *
     * @param socket Socket to connect to
     * @throws IllegalStateException if the client is already running
     */
    public void start(Socket socket) {
        Connection connection = new Connection(socket);
        synchronized (lifecycleLock) {
            if (running) {
                throw new IllegalStateException("VNC Client is already running");
            }
            this.connection = connection;
            running = true;
        }

        try {
            VncSession session = createSession(connection);
            ClientEventHandler clientEventHandler = new ClientEventHandler(session, e -> terminate(connection, e));
            ServerEventHandler serverEventHandler = new ServerEventHandler(session, e -> terminate(connection, e));

            if (config.isBlankRemoteScreen() && connection.isServerInputSupported()) {
                clientEventHandler.setServerInputDisabled(true);
            }

            connection.start(clientEventHandler, serverEventHandler);
        } catch (IOException e) {
            terminate(connection, new UnexpectedVncException(e));
        } catch (VncException e) {
            terminate(connection, e);
        }
    }

    /**
     * Stops the VNC client. The error listener is not called for a connection stopped this way.
     */
    public void stop() {
        Connection connection = this.connection;
        if (connection != null) {
            terminate(connection, null);
        }
    }

    /**
     * Moves the remote mouse pointer to the specified coordinates (relative to the top-left of the screen).
     *
     * @param x The X coordinate
     * @param y The Y coordinate
     */
    public void moveMouse(int x, int y) {
        send(handler -> handler.moveMouse(x, y));
    }

    /**
     * Updates the status (pressed or not pressed) of the specified mouse button.
     * <p>
     * To indicate a mouse 'click', call this method twice in quick succession, first with pressed = true,
     * then with pressed = false, or use the convenience {@link #click(int)} method.
     * <p>
     * On a conventional mouse, buttons 1, 2, and 3 correspond to the left, middle, and right buttons on the mouse.
     * On a wheel mouse, each step of the wheel upwards is represented by a press and release of button 4, and each step
     * downwards is represented by a press and release of button 5.
     *
     * @param button  The mouse button number (1-8)
     * @param pressed Is the mouse button currently 'pressed'?
     */
    public void updateMouseButton(int button, boolean pressed) {
        send(handler -> handler.updateMouseButton(button, pressed));
    }

    /**
     * 'Clicks' (presses and releases) the specified mouse button.
     * <p>
     * This is equivalent to calling {@link #updateMouseButton(int, boolean)} twice in quick succession with
     * pressed = true and pressed = false
     *
     * @param button The mouse button number (1-8)
     */
    public void click(int button) {
        updateMouseButton(button, true);
        updateMouseButton(button, false);
    }

    /**
     * Scroll up using the mouse wheel.
     * <p>
     * This is equivalent to calling {@link #click(int)} with button number 4
     */
    public void scrollUp() {
        click(4);
    }

    /**
     * Scroll down using the mouse wheel.
     * <p>
     * This is equivalent to calling {@link #click(int)} with button number 5
     */
    public void scrollDown() {
        click(5);
    }

    /**
     * Updates the status (pressed or not pressed) of the key represented by the specified KeySym
     * <p>
     * For a complete list of KeySyms, see https://cgit.freedesktop.org/xorg/proto/x11proto/plain/keysymdef.h
     *
     * @param keySym  The KeySym for this key press or release
     * @param pressed Was the key pressed (true) or released (false)?
     */
    public void updateKey(int keySym, boolean pressed) {
        send(handler -> handler.updateKey(keySym, pressed));
    }

    /**
     * 'Types' (presses and releases) the key represented by the specified KeySym.
     * <p>
     * This is equivalent to calling {@link #updateKey(int, boolean)} twice in quick succession with
     * pressed = true and pressed = false
     *
     * @param keySym The KeySym for this key press or release
     */
    public void type(int keySym) {
        updateKey(keySym, true);
        updateKey(keySym, false);
    }

    /**
     * 'Types' (presses and releases) the key representing each character in the specified string, in order.
     * <p>
     * Note that this is only guaranteed to work as expected for strings containing only printable ASCII characters -
     * apart from line breaks, which are converted to ENTER key presses, and tabs, which are converted to TAB key
     * presses.
     *
     * @param text The text that will be typed on the remote server
     */
    public void type(String text) {
        text = text.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
        range(0, text.length())
                .map(text::charAt)
                .map(c -> {
                    switch (c) {
                        case '\n':
                            return 0xff0d; // X11 keysym for Enter
                        case '\t':
                            return 0xff09; // X11 keysym for Tab
                        default:
                            return c;
                    }
                })
                .forEach(this::type);
    }

    /**
     * Copies the specified text to the remote clipboard
     *
     * @param text The text to be copied to the remote clipboard
     */
    public void copyText(String text) {
        send(handler -> handler.copyText(text));
    }

    /**
     * Asks an UltraVNC server to disable (true) or restore (false) its local keyboard and mouse, blanking or
     * unblanking its monitor if the server allows it. Ignored when the connection is stopped or the server was not
     * detected as UltraVNC by security type 17 in its RFB 3.7/3.8 offer. RFB 3.3 servers are never detected.
     * <p>
     * This affects the current connection only; the next {@link #start(Socket)} uses
     * {@link VernacularConfig#isBlankRemoteScreen()} again. Only the viewer that disabled the server's input can
     * restore it. View-only connections are ignored by the server. Blanking requires the server's
     * "Enable Blank Monitor on Viewer Request" setting and "BlankInputsOnly" turned off; otherwise only local input
     * is disabled. The server does not confirm whether the request succeeded.
     *
     * @param blank true to disable input and blank the screen, false to restore them
     * @see VernacularConfig#setBlankRemoteScreen(boolean)
     */
    public void setBlankRemoteScreen(boolean blank) {
        Connection connection = this.connection;
        if (connection != null && connection.isServerInputSupported()) {
            send(connection, handler -> handler.setServerInputDisabled(blank));
        }
    }

    /**
     * Reports whether the live connection supports UltraVNC's SetServerInput extension. Valid once
     * {@link #start(Socket)} or {@link #start(String, int)} returns; recalculated for each connection.
     * <p>
     * Detection requires security type 17 in the server's RFB 3.7/3.8 offer. RFB 3.3 and servers without that offer
     * are not detected, and blanking requests to them are ignored. A detected server may still ignore requests for
     * view-only connections. Only the viewer that disabled input can restore it, and monitor blanking depends on
     * server settings. Support does not confirm that the monitor is blanked.
     *
     * @return true if the connection is live and its server was detected as UltraVNC, false otherwise
     * @see #setBlankRemoteScreen(boolean)
     */
    public boolean isServerInputSupported() {
        Connection connection = this.connection;
        return connection != null && connection.isServerInputSupported();
    }

    /**
     * Is the client currently running?
     *
     * @return true if the client is currently running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    private VncSession createSession(Connection connection) throws IOException, VncException {
        InputStream in = new BufferedInputStream(connection.socket.getInputStream());
        OutputStream out = connection.socket.getOutputStream();
        VncSession session = new VncSession(config, in, out);
        connection.session = session;

        handshaker.handshake(session);
        initializer.initialise(session);
        return session;
    }

    private void send(ClientAction action) {
        send(this.connection, action);
    }

    private void send(Connection connection, ClientAction action) {
        ClientEventHandler handler = connection == null ? null : connection.clientEventHandler;
        if (handler != null) {
            try {
                action.run(handler);
            } catch (IOException e) {
                terminate(connection, new UnexpectedVncException(e));
            }
        }
    }

    // Only the connection's first terminal event counts. It is shut down before the listener runs, so a listener that
    // blocks, throws or reconnects this client can neither keep it alive nor stop its successor.
    private void terminate(Connection connection, VncException error) {
        if (!connection.markTerminated()) {
            return;
        }
        synchronized (lifecycleLock) {
            if (this.connection == connection) {
                running = false;
            }
        }
        connection.close();
        if (error != null) {
            notifyErrorListeners(error);
        }
    }

    private void notifyErrorListeners(VncException e) {
        if (config.getErrorListener() != null) {
            config.getErrorListener().accept(e);
        }
    }

    @FunctionalInterface
    private interface ClientAction {
        void run(ClientEventHandler handler) throws IOException;
    }

    private static final class Connection {

        private final Socket socket;
        private volatile VncSession session;
        private volatile ClientEventHandler clientEventHandler;
        private ServerEventHandler serverEventHandler;
        private volatile boolean terminated;

        Connection(Socket socket) {
            this.socket = socket;
        }

        boolean isServerInputSupported() {
            VncSession session = this.session;
            return !terminated && session != null && session.isServerInputSupported();
        }

        synchronized void start(ClientEventHandler clientEventHandler, ServerEventHandler serverEventHandler) {
            if (terminated) {
                return;
            }
            this.clientEventHandler = clientEventHandler;
            this.serverEventHandler = serverEventHandler;
            serverEventHandler.start();
            clientEventHandler.start();
        }

        synchronized boolean markTerminated() {
            if (terminated) {
                return false;
            }
            terminated = true;
            return true;
        }

        void close() {
            ServerEventHandler serverEventHandler;
            ClientEventHandler clientEventHandler;
            synchronized (this) {
                serverEventHandler = this.serverEventHandler;
                clientEventHandler = this.clientEventHandler;
            }
            VncSession session = this.session;
            if (session != null) {
                session.kill();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (serverEventHandler != null) {
                serverEventHandler.stop();
            }
            if (clientEventHandler != null) {
                clientEventHandler.stop();
            }
        }
    }
}
