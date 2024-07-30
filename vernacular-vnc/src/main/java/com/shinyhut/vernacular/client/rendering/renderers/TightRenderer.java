package com.shinyhut.vernacular.client.rendering.renderers;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;
import com.shinyhut.vernacular.client.exceptions.InvalidTightEncodingException;
import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.client.rendering.ImageBuffer;
import com.shinyhut.vernacular.protocol.messages.ColorMapEntry;
import com.shinyhut.vernacular.protocol.messages.PixelFormat;
import com.shinyhut.vernacular.protocol.messages.Rectangle;

public class TightRenderer implements Renderer
{
   private static final int SUBENCODING_FILL = 0x80;
   private static final int SUBENCODING_JPEG = 0x90;
   private static final int EXPLICIT_FILTER = 0x40;

   private static final int FILTER_COPY = 0;
   private static final int FILTER_PALETTE = 1;

   private static final int MIN_TO_COMPRESS = 12;

   private static final ColorMapEntry BLACK = new ColorMapEntry(0, 0, 0);

   private PixelFormat pixelFormat;
   private Map<Long, ColorMapEntry> colorMap;
   private int redShift;
   private int greenShift;
   private int blueShift;
   private int redMax;
   private int greenMax;
   private int blueMax;
   private Inflater[] inflaters = new Inflater[4];

   public TightRenderer(PixelFormat pixelFormat, Map<Long, ColorMapEntry> colorMap)
   {
      this.pixelFormat = pixelFormat;
      this.colorMap = colorMap;

      redShift = pixelFormat.getRedShift();
      greenShift = pixelFormat.getGreenShift();
      blueShift = pixelFormat.getBlueShift();
      redMax = pixelFormat.getRedMax();
      greenMax = pixelFormat.getGreenMax();
      blueMax = pixelFormat.getBlueMax();
   }

   /**
    * @see com.shinyhut.vernacular.client.rendering.renderers.Renderer#render(java.io.InputStream,
    *      com.shinyhut.vernacular.client.rendering.ImageBuffer, com.shinyhut.vernacular.protocol.messages.Rectangle)
    */
   @Override
   public void render(InputStream in, ImageBuffer destination, Rectangle rect) throws VncException
   {
      try
      {
         DataInput dataInput = new DataInputStream(in);
         int subencoding = dataInput.readUnsignedByte();

         switch(subencoding)
         {
            case SUBENCODING_FILL:
               processSolidColorRectangle(dataInput, destination, rect);
               break;
            case SUBENCODING_JPEG:
               processJpegRectangle(dataInput, destination, rect);
               break;
            default:
               processOtherRectangles(subencoding, dataInput, destination, rect);
               break;
         }
      }
      catch(IOException e)
      {
         throw new UnexpectedVncException(e);
      }
   }

   /**
    * Process solid color rectangle.
    *
    * @param dataInput input stream
    * @param destination destination buffer
    * @param rect affected rectangle
    * @throws IOException on IO error
    */
   private void processSolidColorRectangle(DataInput dataInput, ImageBuffer destination, Rectangle rect) throws IOException
   {
      int color;
      if (pixelFormat.getBytesPerPixel() == 1)
      {
         color = lookupColor(dataInput.readUnsignedByte());
      }
      else if (pixelFormat.getBytesPerPixel() == 2)
      {
         int rgb16;
         if (pixelFormat.isBigEndian())
         {
            rgb16 = dataInput.readUnsignedShort();
         }
         else
         {
            int b1 = dataInput.readUnsignedByte();
            int b2 = dataInput.readUnsignedByte();
            rgb16 = (b2 << 8) | b1;
         }
         color = rgb24FromRgb16(rgb16);
      }
      else
      {
         byte[] buffer = new byte[3];
         dataInput.readFully(buffer);
         color = (buffer[0] & 0xFF) << 16 | (buffer[1] & 0xFF) << 8 | (buffer[2] & 0xFF);
      }
      destination.fillRect(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), color);
   }

   /**
    * Process JPEG rectangle.
    *
    * @param dataInput input stream
    * @param destination destination buffer
    * @param rect affected rectangle
    * @throws IOException on IO error
    * @throws VncException on decoding error
    */
   private void processJpegRectangle(DataInput dataInput, ImageBuffer destination, Rectangle rect) throws IOException, VncException
   {
      int length = readCompactLength(dataInput);
      byte[] data = new byte[length];
      dataInput.readFully(data);
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
      if ((image.getWidth() == rect.getWidth()) && (image.getHeight() == rect.getHeight()))
      {
         for(int y = 0, dy = rect.getY(); y < image.getHeight(); y++, dy++)
         {
            for(int x = 0, dx = rect.getX(); x < image.getWidth(); x++, dx++)
            {
               destination.set(dx, dy, image.getRGB(x, y));
            }
         }
      }
      else
      {
         throw new InvalidTightEncodingException("JPEG image size does not match rectangle size");
      }
   }

   /**
    * Process rectangle types other that JPEG and solid.
    *
    * @param subencoding sub-encoding
    * @param dataInput input stream
    * @param destination destination buffer
    * @param rect affected rectangle
    * @throws IOException on IO error
    * @throws VncException on decoding error
    * @throws DataFormatException on uncompress error
    */
   private void processOtherRectangles(int subencoding, DataInput dataInput, ImageBuffer destination, Rectangle rect) throws IOException, VncException
   {
      int numColors = 0;
      int rowSize = rect.getWidth();
      int[] palette = null;

      if ((subencoding & EXPLICIT_FILTER) != 0)
      {
         int filter = dataInput.readUnsignedByte();
         if (filter == FILTER_PALETTE)
         {
            numColors = dataInput.readUnsignedByte() + 1;
            palette = new int[256];
            if (pixelFormat.getBytesPerPixel() >= 3)
            {
               read24BitPalette(dataInput, palette, numColors);
            }
            else if (pixelFormat.getBytesPerPixel() == 2)
            {
               read16BitPalette(dataInput, palette, numColors);
            }
            else
            {
               read8BitPalette(dataInput, palette, numColors);
            }
            if (numColors == 2)
               rowSize = (rect.getWidth() + 7) / 8;
         }
         else if (filter != FILTER_COPY)
         {
            throw new InvalidTightEncodingException("Unsupported tight filter " + filter);
         }
      }

      if (numColors == 0)
         rowSize *= Math.min(pixelFormat.getBytesPerPixel(), 3);

      byte[] data;
      int dataSize = rect.getHeight() * rowSize;
      if (dataSize >= MIN_TO_COMPRESS)
      {
         int zlibDataLen = readCompactLength(dataInput);
         byte[] zlibData = new byte[zlibDataLen];
         dataInput.readFully(zlibData);
         int streamId = (subencoding >> 4) & 0x03;
         if (inflaters[streamId] == null)
         {
            inflaters[streamId] = new Inflater();
         }
         Inflater inflater = inflaters[streamId];
         inflater.setInput(zlibData);
         data = new byte[dataSize];
         try
         {
            inflater.inflate(data);
         }
         catch(DataFormatException e)
         {
            throw new UnexpectedVncException(e);
         }
      }
      else
      {
         data = new byte[dataSize];
         dataInput.readFully(data);
      }

      if (numColors == 0)
      {
         // Full-color rectangle
         if (pixelFormat.getBytesPerPixel() == 1)
         {
            int i = 0;
            for(int y = 0, dy = rect.getY(); y < rect.getHeight(); y++, dy++)
            {
               for(int x = 0, dx = rect.getX(); x < rect.getWidth(); x++, dx++)
               {
                  destination.set(dx, dy, lookupColor(data[i++] & 0xFF));
               }
            }
         }
         else if (pixelFormat.getBytesPerPixel() == 2)
         {
            int i = 0;
            boolean bigEndian = pixelFormat.isBigEndian();
            for(int y = 0, dy = rect.getY(); y < rect.getHeight(); y++, dy++)
            {
               for(int x = 0, dx = rect.getX(); x < rect.getWidth(); x++, dx++, i += 2)
               {
                  int rgb16 = bigEndian ? (((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF)) : (((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF));
                  destination.set(dx, dy, rgb24FromRgb16(rgb16));
               }
            }
         }
         else
         {
            int i = 0;
            for(int y = 0, dy = rect.getY(); y < rect.getHeight(); y++, dy++)
            {
               for(int x = 0, dx = rect.getX(); x < rect.getWidth(); x++, dx++)
               {
                  int r = (data[i++] & 0xFF) << 16;
                  int g = (data[i++] & 0xFF) << 8;
                  int b = data[i++] & 0xFF;
                  destination.set(dx, dy, r | g | b);
               }
            }
         }
      }
      else if (numColors == 2)
      {
         // Two-color rectangle, each pixel represented by single bit
         int i = 0;
         for(int y = 0, dy = rect.getY(); y < rect.getHeight(); y++, dy++)
         {
            for(int x = 0, dx = rect.getX(); x < rect.getWidth();)
            {
               byte b = data[i++];
               for(int n = 7; (n >= 0) && (x < rect.getWidth()); n--, x++)
               {
                  destination.set(dx++, dy, palette[(b >> n) & 1]);
               }
            }
         }
      }
      else
      {
         // Indexed color rectangle, each byte represents color index in palette
         int i = 0;
         for(int y = 0, dy = rect.getY(); y < rect.getHeight(); y++, dy++)
         {
            for(int x = 0, dx = rect.getX(); x < rect.getWidth(); x++, dx++)
            {
               destination.set(dx, dy, palette[data[i++] & 0xFF]);
            }
         }
      }
   }

   /**
    * Read length field encoded as 1, 2, or 3 bytes.
    *
    * @param dataInput input stream
    * @return decoded length
    * @throws IOException on I/O error
    */
   private int readCompactLength(DataInput dataInput) throws IOException
   {
      int[] bytes = new int[3];
      bytes[0] = dataInput.readUnsignedByte();
      int length = bytes[0] & 0x7F;
      if ((bytes[0] & 0x80) != 0)
      {
         bytes[1] = dataInput.readUnsignedByte();
         length |= (bytes[1] & 0x7F) << 7;
         if ((bytes[1] & 0x80) != 0)
         {
            bytes[2] = dataInput.readUnsignedByte();
            length |= (bytes[2] & 0xFF) << 14;
         }
      }
      return length;
   }

   /**
    * Read 24-bit palette.
    *
    * @param dataInput input stream
    * @param palette palette to fill
    * @param numColors number of colors in palette
    * @throws IOException on I/O error
    */
   private void read24BitPalette(DataInput dataInput, int[] palette, int numColors) throws IOException
   {
      byte[] bytes = new byte[3];
      if (pixelFormat.isBigEndian())
      {
         for(int i = 0; i < numColors; i++)
         {
            dataInput.readFully(bytes);
            palette[i] = (bytes[0] & 0xFF) << 16 | (bytes[1] & 0xFF) << 8 | (bytes[2] & 0xFF);
         }
      }
      else
      {
         for(int i = 0; i < numColors; i++)
         {
            dataInput.readFully(bytes);
            palette[i] = (bytes[2] & 0xFF) << 16 | (bytes[1] & 0xFF) << 8 | (bytes[0] & 0xFF);
         }
      }
   }

   /**
    * Read 16-bit palette.
    *
    * @param dataInput input stream
    * @param palette palette to fill
    * @param numColors number of colors in palette
    * @throws IOException on I/O error
    */
   private void read16BitPalette(DataInput dataInput, int[] palette, int numColors) throws IOException
   {
      if (pixelFormat.isBigEndian())
      {
         for(int i = 0; i < numColors; i++)
            palette[i] = rgb24FromRgb16(dataInput.readUnsignedShort());
      }
      else
      {
         for(int i = 0; i < numColors; i++)
         {
            int b1 = dataInput.readUnsignedByte();
            int b2 = dataInput.readUnsignedByte();
            palette[i] = rgb24FromRgb16((b2 << 8) | b1);
         }
      }
   }

   /**
    * Read 8-bit palette.
    *
    * @param dataInput input stream
    * @param palette palette to fill
    * @param numColors number of colors in palette
    * @throws IOException on I/O error
    */
   private void read8BitPalette(DataInput dataInput, int[] palette, int numColors) throws IOException
   {
      for(int i = 0; i < numColors; i++)
         palette[i] = lookupColor(dataInput.readUnsignedByte());
   }

   /**
    * Convert 16 bit true color to 24 bit true color.
    *
    * @param rgb16 16-bit color value
    * @return
    */
   private int rgb24FromRgb16(int rgb16)
   {
      int r = ((rgb16 >> redShift) & pixelFormat.getRedMax()) * 255 / redMax;
      int g = ((rgb16 >> greenShift) & greenMax) * 255 / greenMax;
      int b = ((rgb16 >> blueShift) & blueMax) * 255 / blueMax;
      return (r << 16) | (g << 8) | b;
   }

   /**
    * Lookup color in color map by index.
    *
    * @param index color index
    * @return RGB color
    */
   private int lookupColor(int index)
   {
      ColorMapEntry color = Optional.ofNullable(colorMap.get(Long.valueOf(index))).orElse(BLACK);
      int red = shrink(color.getRed());
      int green = shrink(color.getGreen());
      int blue = shrink(color.getBlue());
      return (red << 16) | (green << 8) | blue;
   }

   /**
    * Shrink color map value
    *
    * @param colorMapValue color map value for single color
    * @return value in range 0..255
    */
   private static int shrink(int colorMapValue)
   {
      return (int)Math.round(((double)colorMapValue) / 257);
   }
}
