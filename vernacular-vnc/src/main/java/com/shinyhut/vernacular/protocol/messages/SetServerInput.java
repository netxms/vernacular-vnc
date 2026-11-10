package com.shinyhut.vernacular.protocol.messages;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * UltraVNC extension (rfbSetServerInput): asks the server to disable or re-enable its local keyboard and mouse,
 * optionally blanking its monitor.
 */
public class SetServerInput implements Encodable {

    private final boolean disabled;

    public SetServerInput(boolean disabled) {
        this.disabled = disabled;
    }

    @Override
    public void encode(OutputStream out) throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(out);
        dataOutput.writeByte(0x09);
        dataOutput.writeByte(disabled ? 1 : 0);
        dataOutput.writeShort(0);
    }
}
