package com.shinyhut.vernacular.client.exceptions;

public class UnexpectedVncException extends VncException
{

   private static final long serialVersionUID = 1L;

   public UnexpectedVncException(Throwable cause)
   {
      super("An unexpected exception occurred: " + cause.getClass().getSimpleName(), cause);
   }

}
