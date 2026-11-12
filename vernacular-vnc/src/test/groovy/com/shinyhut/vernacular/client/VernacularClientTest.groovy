package com.shinyhut.vernacular.client

import com.shinyhut.vernacular.client.exceptions.ConnectionClosedException
import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException
import com.shinyhut.vernacular.client.exceptions.VncException
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static java.util.concurrent.TimeUnit.SECONDS

class VernacularClientTest extends Specification {

    static final int SET_ENCODINGS_OFFSET = 12 + 1 + 1 + 20 // ProtocolVersion, security type, ClientInit, SetPixelFormat
    static final int SET_SERVER_INPUT = 9
    static final int FRAMEBUFFER_UPDATE_REQUEST = 3
    static final long JOIN_TIMEOUT_MILLIS = 1000
    static final Map<Integer, Integer> MESSAGE_LENGTHS = [(SET_SERVER_INPUT): 4, (FRAMEBUFFER_UPDATE_REQUEST): 10]

    def config = new VernacularConfig()
    def server = new FakeServer()
    def client = new VernacularClient(config)
    def errors = new CopyOnWriteArrayList<VncException>()
    def conditions = new PollingConditions(timeout: 5)
    def threadsBefore = clientThreads()

    def setup() {
        config.errorListener = { VncException e -> errors << e }
    }

    def cleanup() {
        client.stop()
        server.release()
    }

    def "should not send SetServerInput on connect when screen blanking is off"() {
        given:
        server = new FakeServer(8, [17, 1])

        when:
        client.start(server.socket())

        then:
        conditions.eventually { assert server.messagesAfterSetEncodings()*.getAt(0) == [FRAMEBUFFER_UPDATE_REQUEST] }
    }

    def "should send SetServerInput right after SetEncodings when screen blanking is on"() {
        given:
        server = new FakeServer(8, [17, 1])
        config.blankRemoteScreen = true

        when:
        client.start(server.socket())

        then:
        conditions.eventually { assert server.messagesAfterSetEncodings().size() == 2 }
        server.messagesAfterSetEncodings()[0] == [SET_SERVER_INPUT, 1, 0, 0]
        server.messagesAfterSetEncodings()[1][0] == FRAMEBUFFER_UPDATE_REQUEST
    }

    def "should send SetServerInput when screen blanking is toggled on a running client"() {
        given:
        server = new FakeServer(8, [1, 17])
        startLiveSession()

        when:
        client.setBlankRemoteScreen(true)
        client.setBlankRemoteScreen(false)

        then:
        server.messagesAfterSetEncodings().drop(1) == [[SET_SERVER_INPUT, 1, 0, 0], [SET_SERVER_INPUT, 0, 0, 0]]
    }

    def "should detect RFB 3.#minor UltraVNC and send blanking before the first framebuffer request"() {
        given:
        server = new FakeServer(minor, types)
        config.blankRemoteScreen = true

        when:
        startLiveSession()

        then:
        client.serverInputSupported
        server.messagesAfterSetEncodings()*.getAt(0) == [SET_SERVER_INPUT, FRAMEBUFFER_UPDATE_REQUEST]
        server.messagesAfterSetEncodings()[0] == [SET_SERVER_INPUT, 1, 0, 0]
        errors.isEmpty()

        where:
        minor | types
        7     | [17, 1]
        7     | [1, 17]
        8     | [17, 1]
        8     | [1, 17]
    }

    def "should ignore configured and runtime blanking on undetected RFB 3.#minor servers"() {
        given:
        server = new FakeServer(minor)
        config.blankRemoteScreen = true
        startLiveSession()
        def writes = server.output.writeCalls.get()

        when:
        client.setBlankRemoteScreen(true)
        client.setBlankRemoteScreen(false)

        then:
        !client.serverInputSupported
        client.running
        server.output.writeCalls.get() == writes
        server.messagesAfterSetEncodings()*.getAt(0) == [FRAMEBUFFER_UPDATE_REQUEST]
        errors.isEmpty()

        where:
        minor << [3, 7, 8]
    }

    def "should ignore runtime blanking before connecting and after stopping a detected connection"() {
        expect:
        !client.serverInputSupported

        when:
        client.setBlankRemoteScreen(true)
        server = new FakeServer(8, [17, 1])
        startLiveSession()

        then:
        client.serverInputSupported

        when:
        client.stop()
        def writes = server.output.writeCalls.get()
        client.setBlankRemoteScreen(true)
        client.setBlankRemoteScreen(false)

        then:
        !client.serverInputSupported
        server.output.writeCalls.get() == writes
        errors.isEmpty()
        conditions.eventually { assert newClientThreads().isEmpty() }
    }

    def "should reset UltraVNC detection when a client reconnects to an undetected server"() {
        given:
        config.blankRemoteScreen = true
        server = new FakeServer(8, [17, 1])
        startLiveSession()
        assert client.serverInputSupported
        client.stop()
        def nextServer = new FakeServer()

        when:
        client.start(nextServer.socket())
        assert nextServer.input.reachedEnd.await(5, SECONDS)
        conditions.eventually { assert nextServer.messagesAfterSetEncodings().size() == 1 }
        def writes = nextServer.output.writeCalls.get()
        client.setBlankRemoteScreen(true)

        then:
        !client.serverInputSupported
        client.running
        nextServer.output.writeCalls.get() == writes
        nextServer.messagesAfterSetEncodings()*.getAt(0) == [FRAMEBUFFER_UPDATE_REQUEST]
        errors.isEmpty()

        cleanup:
        client.stop()
        nextServer.release()
    }

    def "should report a write failure on an active connection and stop"() {
        given:
        startLiveSession()
        server.output.failing = true

        when:
        client.moveMouse(1, 1)

        then:
        errors.size() == 1
        errors[0] instanceof UnexpectedVncException
        !client.running
        conditions.eventually { assert newClientThreads().isEmpty() }
    }

    def "should report a server EOF right after ServerInit and stop"() {
        given:
        server.input.releaseEof()

        when:
        client.start(server.socket())

        then:
        conditions.eventually { assert errors.size() == 1 }
        errors[0] instanceof ConnectionClosedException
        conditions.eventually { assert newClientThreads().isEmpty() }
        !client.running
        errors.size() == 1
    }

    def "should report a server EOF on a live session once, with the client already stopped"() {
        given:
        def runningInListener = []
        config.errorListener = { VncException e -> runningInListener << client.running; errors << e }
        startLiveSession()

        when:
        server.input.releaseEof()

        then:
        conditions.eventually { assert newClientThreads().isEmpty() }
        errors.size() == 1
        errors[0] instanceof ConnectionClosedException
        runningInListener == [false]
        !client.running
    }

    def "should shut the connection down before a blocking listener is called"() {
        given:
        def inListener = new CountDownLatch(1)
        def releaseListener = new CountDownLatch(1)
        def stateInListener = [:]
        config.errorListener = { VncException e ->
            stateInListener.running = client.running
            stateInListener.inputClosed = server.input.closed
            stateInListener.otherThreads = (newClientThreads() - Thread.currentThread()) as List
            inListener.countDown()
            releaseListener.await()
        }
        startLiveSession()

        when:
        server.input.releaseEof()

        then:
        inListener.await(5, SECONDS)
        stateInListener == [running: false, inputClosed: true, otherThreads: []]

        when:
        releaseListener.countDown()

        then:
        conditions.eventually { assert newClientThreads().isEmpty() }
    }

    def "should shut the connection down even when the listener throws"() {
        given:
        def called = new CountDownLatch(1)
        config.errorListener = { VncException e ->
            called.countDown()
            throw new IllegalStateException('listener failed')
        }
        startLiveSession()

        when:
        server.input.releaseEof()

        then:
        called.await(5, SECONDS)
        conditions.eventually { assert newClientThreads().isEmpty() }
        server.input.closed
        !client.running
    }

    def "should shut the connection down before reporting a write failure"() {
        given:
        def threadsInListener = null
        config.errorListener = { VncException e -> threadsInListener = newClientThreads(); errors << e }
        startLiveSession()
        server.output.failing = true

        when:
        def elapsed = millis { client.moveMouse(1, 1) }

        then:
        errors.size() == 1
        threadsInListener.isEmpty()
        server.input.closed
        elapsed < JOIN_TIMEOUT_MILLIS
    }

    def "should leave no client threads alive when stop returns"() {
        given:
        startLiveSession()

        when:
        def elapsed = millis { client.stop() }

        then:
        newClientThreads().isEmpty()
        server.input.closed
        elapsed < JOIN_TIMEOUT_MILLIS
    }

    def "should not report an EOF after a local stop"() {
        given:
        startLiveSession()

        when:
        client.stop()
        server.input.releaseEof()

        then:
        conditions.eventually { assert newClientThreads().isEmpty() }
        server.input.closed
        errors.isEmpty()
        !client.running
    }

    def "should not report a blocked write that fails after a local stop"() {
        given:
        startLiveSession()
        def writer = server.output.blockNextWrite()
        def sender = Thread.start { client.moveMouse(1, 1) }
        writer.entered.await(5, SECONDS)

        when:
        client.stop()
        writer.fail()
        sender.join(5000)

        then:
        !sender.alive
        conditions.eventually { assert newClientThreads().isEmpty() }
        errors.isEmpty()
        !client.running
    }

    def "should report a simultaneous EOF and write failure once"() {
        given:
        startLiveSession()
        def writer = server.output.blockNextWrite()
        def sender = Thread.start { client.moveMouse(1, 1) }
        writer.entered.await(5, SECONDS)

        when:
        server.input.releaseEof()
        writer.fail()
        sender.join(5000)

        then:
        !sender.alive
        conditions.eventually { assert newClientThreads().isEmpty() }
        errors.size() == 1
        !client.running
    }

    def "should report a refused connection"() {
        given:
        def port = new ServerSocket(0).withCloseable { it.localPort }

        when:
        client.start('127.0.0.1', port)

        then:
        errors.size() == 1
        errors[0] instanceof UnexpectedVncException
        errors[0].cause instanceof ConnectException
        !client.running
    }

    def "should close the socket start(host, port) opened when the client is already running"() {
        given:
        startLiveSession()
        def listener = new ServerSocket(0)

        when:
        client.start('127.0.0.1', listener.localPort)

        then:
        thrown(IllegalStateException)

        when:
        def accepted = listener.accept()
        accepted.soTimeout = 5000

        then:
        accepted.inputStream.read() == -1
        errors.isEmpty()
        client.running
        !server.input.closed
        newClientThreads().size() == 2

        cleanup:
        accepted?.close()
        listener.close()
    }

    def "should not let an old session's EOF stop a session reconnected from its listener"() {
        given:
        def nextServer = new FakeServer()
        config.errorListener = { VncException e ->
            errors << e
            if (errors.size() == 1) {
                client.start(nextServer.socket())
            }
        }
        startLiveSession()

        when:
        server.input.releaseEof()

        then:
        conditions.eventually { assert errors.size() == 1 && nextServer.input.reachedEnd.count == 0 }
        conditions.eventually { assert newClientThreads().size() == 2 }
        client.running
        !nextServer.input.closed

        cleanup:
        client.stop()
        nextServer.release()
    }

    def "should not let an old session's late write failure stop a reconnected session"() {
        given:
        startLiveSession()
        def writer = server.output.blockNextWrite()
        def sender = Thread.start { client.moveMouse(1, 1) }
        writer.entered.await(5, SECONDS)
        client.stop()
        def nextServer = new FakeServer()
        client.start(nextServer.socket())
        nextServer.input.reachedEnd.await(5, SECONDS)

        when:
        writer.fail()
        sender.join(5000)

        then:
        !sender.alive
        errors.isEmpty()
        client.running
        !nextServer.input.closed
        newClientThreads().size() == 2

        cleanup:
        client.stop()
        nextServer.release()
    }

    private void startLiveSession() {
        client.start(server.socket())
        assert server.input.reachedEnd.await(5, SECONDS)
        conditions.eventually { assert server.messagesAfterSetEncodings()*.getAt(0)?.last() == FRAMEBUFFER_UPDATE_REQUEST }
    }

    // A handler joined before the transport closes is still blocked in read() and holds stop() for the full join timeout
    private static long millis(Closure action) {
        long start = System.nanoTime()
        action()
        (System.nanoTime() - start).intdiv(1_000_000)
    }

    private static Set<Thread> clientThreads() {
        Thread.allStackTraces.keySet().findAll { it.alive && it.name.startsWith('vernacular-') } as Set
    }

    private Set<Thread> newClientThreads() {
        clientThreads() - threadsBefore
    }

    static class FakeServer {
        final ServerInput input
        final ServerOutput output = new ServerOutput()
        final int setEncodingsOffset

        FakeServer(int minor = 8, List<Integer> types = [1]) {
            setEncodingsOffset = SET_ENCODINGS_OFFSET - (minor == 3 ? 1 : 0)
            def afterVersion = new ByteArrayOutputStream()
            afterVersion.write((minor == 3 ? [0, 0, 0, 1] : [types.size()] + types) as byte[])
            if (minor >= 8) {
                afterVersion.write([0x00, 0x00, 0x00, 0x00] as byte[]) // SecurityResult: OK for None in 3.8
            }
            afterVersion.write([0x00, 0x20, 0x00, 0x20] as byte[]) // ServerInit: 32x32
            afterVersion.write(new byte[16]) // pixel format
            afterVersion.write([0x00, 0x00, 0x00, 0x00] as byte[]) // empty server name
            input = new ServerInput(["RFB 003.00${minor}\n".bytes, afterVersion.toByteArray()])
        }

        Socket socket() {
            new Socket() {
                @Override
                InputStream getInputStream() { input }

                @Override
                OutputStream getOutputStream() { output }
            }
        }

        void release() {
            input.releaseEof()
            output.release()
        }

        List<List<Integer>> messagesAfterSetEncodings() {
            def bytes = output.toByteArray().collect { it & 0xFF }
            assert bytes[setEncodingsOffset] == 0x02
            int encodingCount = (bytes[setEncodingsOffset + 2] << 8) | bytes[setEncodingsOffset + 3]
            int position = setEncodingsOffset + 4 + 4 * encodingCount
            def messages = []
            while (position < bytes.size()) {
                int length = MESSAGE_LENGTHS[bytes[position]]
                assert length
                assert position + length <= bytes.size()
                messages << bytes.subList(position, position + length)
                position += length
            }
            messages
        }
    }

    // Serves the handshake in chunks, then blocks like a live server until the test releases EOF or the client closes
    // the stream. ProtocolVersion.decode reads through a BufferedReader, which swallows whatever is already available,
    // so the version line must arrive on its own like it does from a real server.
    static class ServerInput extends InputStream {
        final CountDownLatch reachedEnd = new CountDownLatch(1)
        private final CountDownLatch end = new CountDownLatch(1)
        private final Deque<ByteArrayInputStream> chunks
        private volatile boolean eof
        volatile boolean closed

        ServerInput(List<byte[]> chunks) {
            this.chunks = new ArrayDeque<>(chunks.collect { new ByteArrayInputStream(it) })
        }

        void releaseEof() {
            eof = true
            end.countDown()
        }

        @Override
        int read() {
            byte[] b = new byte[1]
            read(b, 0, 1) == -1 ? -1 : (b[0] & 0xFF)
        }

        @Override
        int read(byte[] b, int off, int len) {
            while (!chunks.isEmpty() && chunks.peek().available() == 0) {
                chunks.poll()
            }
            if (!chunks.isEmpty()) {
                return chunks.peek().read(b, off, len)
            }
            reachedEnd.countDown()
            end.await()
            if (eof) {
                return -1
            }
            throw new IOException('stream closed')
        }

        @Override
        int available() {
            chunks.isEmpty() ? 0 : chunks.peek().available()
        }

        @Override
        void close() {
            closed = true
            end.countDown()
        }
    }

    static class ServerOutput extends ByteArrayOutputStream {
        volatile boolean failing
        final AtomicInteger writeCalls = new AtomicInteger()
        private volatile BlockedWrite blocked

        BlockedWrite blockNextWrite() {
            blocked = new BlockedWrite()
        }

        void release() {
            blocked?.fail()
        }

        @Override
        void write(int b) {
            writeCalls.incrementAndGet()
            def blocked = this.blocked
            if (blocked != null) {
                this.blocked = null
                blocked.entered.countDown()
                blocked.release.await()
                throw new IOException('write failed')
            }
            if (failing) {
                throw new IOException('write failed')
            }
            synchronized (this) {
                super.write(b)
            }
        }
    }

    static class BlockedWrite {
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch release = new CountDownLatch(1)

        void fail() {
            release.countDown()
        }
    }
}
