package com.shinyhut.vernacular.client.exceptions;

public class ConnectionClosedException extends VncException {

    private static final long serialVersionUID = 1L;

    public ConnectionClosedException() {
        super("Connection closed by server");
    }

}
