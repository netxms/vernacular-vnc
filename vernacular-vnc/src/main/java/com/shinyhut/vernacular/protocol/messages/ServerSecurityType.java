package com.shinyhut.vernacular.protocol.messages;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import com.shinyhut.vernacular.client.exceptions.HandshakingFailedException;
import com.shinyhut.vernacular.client.exceptions.NoSupportedSecurityTypesException;

public class ServerSecurityType {

    private final SecurityType securityType;

    private ServerSecurityType(SecurityType securityType) {
        this.securityType = securityType;
    }

    public SecurityType getSecurityType() {
        return securityType;
    }

    public static Integer decode(InputStream in) throws HandshakingFailedException, NoSupportedSecurityTypesException, IOException {
        DataInputStream dataInput = new DataInputStream(in);
        int type = dataInput.readInt();

        if (type == 0) {
            ErrorMessage errorMessage = ErrorMessage.decode(in);
            throw new HandshakingFailedException(errorMessage.getMessage());
        }

        return type;
    }
}
