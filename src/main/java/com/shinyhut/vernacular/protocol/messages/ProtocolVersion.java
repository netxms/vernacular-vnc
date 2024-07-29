package com.shinyhut.vernacular.protocol.messages;

import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.shinyhut.vernacular.client.exceptions.InvalidMessageException;
import com.shinyhut.vernacular.client.exceptions.VncException;

public class ProtocolVersion implements Encodable {

    private static final Pattern PROTOCOL_VERSION_MESSAGE = Pattern.compile("RFB (\\d{3})\\.(\\d{3})");

    private final int major;
    private final int minor;

    public ProtocolVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    @Override
    public void encode(OutputStream out) throws IOException {
        out.write(format("RFB %03d.%03d\n", major, minor).getBytes(Charset.forName("US-ASCII")));
    }

    public boolean equals(int major, int minor) {
        return this.major == major && this.minor == minor;
    }

    public boolean atLeast(int major, int minor) {
        return this.major > major || (this.major == major && this.minor >= minor);
    }

    public static ProtocolVersion decode(InputStream in) throws VncException, IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        String message = br.readLine();
        if (message == null) {
            throw new IOException("Received no response from server. Is a VNC server running?");
        }

        Matcher matcher = PROTOCOL_VERSION_MESSAGE.matcher(message);
        if (matcher.matches()) {
            String major = matcher.group(1);
            String minor = matcher.group(2);
            return new ProtocolVersion(parseInt(major), parseInt(minor));
        } else {
            throw new InvalidMessageException("ProtocolVersion");
        }
    }

}
