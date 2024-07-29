package com.shinyhut.vernacular.client.exceptions;

public class AuthenticationRequiredException extends VncException {
   private static final long serialVersionUID = 1L;

   public AuthenticationRequiredException() {
        super("Server requires authentication but no username or password supplier was provided");
    }
}
