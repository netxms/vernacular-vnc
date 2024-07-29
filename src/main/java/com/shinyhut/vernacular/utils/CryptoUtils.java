package com.shinyhut.vernacular.utils;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.VncException;

public class CryptoUtils
{

   public static MessageDigest sha1() throws VncException
   {
      try
      {
         return MessageDigest.getInstance("SHA-1");
      }
      catch(NoSuchAlgorithmException e)
      {
         throw new UnexpectedVncException(e);
      }
   }

   public static MessageDigest sha256() throws VncException
   {
      try
      {
         return MessageDigest.getInstance("SHA-256");
      }
      catch(NoSuchAlgorithmException e)
      {
         throw new UnexpectedVncException(e);
      }
   }

   public static KeyPairGenerator rsaKeyPairGenerator() throws VncException
   {
      try
      {
         return KeyPairGenerator.getInstance("RSA");
      }
      catch(NoSuchAlgorithmException e)
      {
         throw new UnexpectedVncException(e);
      }
   }

   public static KeyFactory rsaKeyFactory() throws VncException
   {
      try
      {
         return KeyFactory.getInstance("RSA");
      }
      catch(NoSuchAlgorithmException e)
      {
         throw new UnexpectedVncException(e);
      }
   }

   public static Cipher rsaEcbPkcs1PaddingCipher() throws VncException
   {
      try
      {
         return Cipher.getInstance("RSA/ECB/PKCS1Padding");
      }
      catch(Exception e)
      {
         throw new UnexpectedVncException(e);
      }
   }
}
