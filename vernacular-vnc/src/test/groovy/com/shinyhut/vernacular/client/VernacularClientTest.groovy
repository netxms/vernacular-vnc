package com.shinyhut.vernacular.client

import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException
import com.shinyhut.vernacular.client.exceptions.VncException
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class VernacularClientTest extends Specification {

    static final int SET_ENCODINGS_OFFSET = 12 + 1 + 1 + 20 // ProtocolVersion, security type, ClientInit, SetPixelFormat
    static final int SET_SERVER_INPUT = 9
    static final int FRAMEBUFFER_UPDATE_REQUEST = 3
    static final Map<Integer, Integer> MESSAGE_LENGTHS = [(SET_SERVER_INPUT): 4, (FRAMEBUFFER_UPDATE_REQUEST): 10]

    def config = new VernacularConfig()
    def output = new FailingOutputStream()
    def client = new VernacularClient(config)
    def conditions = new PollingConditions(timeout: 5)

    def cleanup() {
        client.stop()
    }

    def "should not send SetServerInput on connect when screen blanking is off"() {
        when:
        client.start(serverSocket())

        then:
        conditions.eventually { assert messagesAfterSetEncodings()*.getAt(0) == [FRAMEBUFFER_UPDATE_REQUEST] }
    }

    def "should send SetServerInput right after SetEncodings when screen blanking is on"() {
        given:
        config.blankRemoteScreen = true

        when:
        client.start(serverSocket())

        then:
        conditions.eventually { assert messagesAfterSetEncodings().size() == 2 }
        messagesAfterSetEncodings()[0] == [SET_SERVER_INPUT, 1, 0, 0]
        messagesAfterSetEncodings()[1][0] == FRAMEBUFFER_UPDATE_REQUEST
    }

    def "should send SetServerInput when screen blanking is toggled on a running client"() {
        given:
        client.start(serverSocket())
        conditions.eventually { assert messagesAfterSetEncodings().size() == 1 }

        when:
        client.setBlankRemoteScreen(true)
        client.setBlankRemoteScreen(false)

        then:
        messagesAfterSetEncodings().drop(1) == [[SET_SERVER_INPUT, 1, 0, 0], [SET_SERVER_INPUT, 0, 0, 0]]
    }

    def "should report a write failure when toggling screen blanking"() {
        given:
        def errors = []
        config.errorListener = { VncException e -> errors << e }
        client.start(serverSocket())
        conditions.eventually { assert messagesAfterSetEncodings().size() == 1 }
        output.failing = true

        when:
        client.setBlankRemoteScreen(true)

        then:
        errors.size() == 1
        errors[0] instanceof UnexpectedVncException
        !client.running
    }

    private Socket serverSocket() {
        def afterVersion = new ByteArrayOutputStream()
        afterVersion.write([0x01, 0x01] as byte[]) // one security type: None
        afterVersion.write([0x00, 0x00, 0x00, 0x00] as byte[]) // SecurityResult: OK
        afterVersion.write([0x00, 0x20, 0x00, 0x20] as byte[]) // ServerInit: 32x32
        afterVersion.write(new byte[16]) // pixel format
        afterVersion.write([0x00, 0x00, 0x00, 0x00] as byte[]) // empty server name

        def input = new ChunkedInputStream(['RFB 003.008\n'.bytes, afterVersion.toByteArray()])
        Stub(Socket) {
            getInputStream() >> input
            getOutputStream() >> output
        }
    }

    private List<List<Integer>> messagesAfterSetEncodings() {
        def bytes = output.toByteArray().collect { it & 0xFF }
        assert bytes[SET_ENCODINGS_OFFSET] == 0x02
        int encodingCount = (bytes[SET_ENCODINGS_OFFSET + 2] << 8) | bytes[SET_ENCODINGS_OFFSET + 3]
        int position = SET_ENCODINGS_OFFSET + 4 + 4 * encodingCount
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

    // ProtocolVersion.decode reads through a BufferedReader, which swallows whatever is already available, so the
    // version line must arrive on its own like it does from a real server
    static class ChunkedInputStream extends InputStream {
        private final Deque<ByteArrayInputStream> chunks

        ChunkedInputStream(List<byte[]> chunks) {
            this.chunks = new ArrayDeque<>(chunks.collect { new ByteArrayInputStream(it) })
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
            chunks.isEmpty() ? -1 : chunks.peek().read(b, off, len)
        }

        @Override
        int available() {
            chunks.isEmpty() ? 0 : chunks.peek().available()
        }
    }

    static class FailingOutputStream extends ByteArrayOutputStream {
        volatile boolean failing

        @Override
        synchronized void write(int b) {
            if (failing) {
                throw new IOException('write failed')
            }
            super.write(b)
        }
    }
}
