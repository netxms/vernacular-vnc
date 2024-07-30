package com.shinyhut.vernacular.client.exceptions;

import java.util.List;
import java.util.stream.Collectors;
import com.shinyhut.vernacular.protocol.messages.SecurityType;

public class NoSupportedSecurityTypesException extends VncException
{
   private static final long serialVersionUID = 1L;

   private final List<Integer> available;
   private final List<SecurityType> supported;

   public NoSupportedSecurityTypesException(List<Integer> available, List<SecurityType> supported)
   {
      super("The server does not support any VNC security types supported by this client." + "\nAvailable: " + available.stream().map(Object::toString).collect(Collectors.joining(", ")) +
            "\nSupported:\n" + formatSupported(supported));
      this.available = available;
      this.supported = supported;
   }

   public List<Integer> getAvailable()
   {
      return available;
   }

   public List<SecurityType> getSupported()
   {
      return supported;
   }

   private static String formatSupported(List<SecurityType> supported)
   {
      return supported.stream().map(securityType -> "- " + securityType.getName() + " (" + securityType.getCode() + ")").collect(Collectors.joining("\n"));
   }
}
