package com.shinyhut.vernacular.client.exceptions;

public class UnexpectedVncException extends VncException
{

   private static final long serialVersionUID = 1L;

   public UnexpectedVncException(String message)
   {
      super(message);
   }

   public UnexpectedVncException(String message, Throwable cause)
   {
      super(message, cause);
   }

   public UnexpectedVncException(Throwable cause)
   {
      super("An unexpected exception occurred: " + cause.getClass().getSimpleName(), cause);
   }

}
