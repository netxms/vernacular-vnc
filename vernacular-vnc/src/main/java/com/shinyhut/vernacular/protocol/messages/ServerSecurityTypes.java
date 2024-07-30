package com.shinyhut.vernacular.protocol.messages;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import com.shinyhut.vernacular.client.exceptions.HandshakingFailedException;

public class ServerSecurityTypes {

    private final List<Integer> securityTypes;

    private ServerSecurityTypes(List<Integer> securityTypes) {
        this.securityTypes = securityTypes;
    }

    public List<Integer> getSecurityTypes() {
        return securityTypes;
    }

    public static ServerSecurityTypes decode(InputStream in) throws HandshakingFailedException, IOException {
        DataInputStream dataInput = new DataInputStream(in);
        byte typeCount = dataInput.readByte();

        if (typeCount == 0) {
            ErrorMessage errorMessage = ErrorMessage.decode(in);
            throw new HandshakingFailedException(errorMessage.getMessage());
        }

        List<Integer> types = new ArrayList<>();

        for (int i = 0; i < typeCount; i++) {
            byte type = dataInput.readByte();
            var code = type & 0xFF;
            types.add(code);
        }

        return new ServerSecurityTypes(types);
    }
}
