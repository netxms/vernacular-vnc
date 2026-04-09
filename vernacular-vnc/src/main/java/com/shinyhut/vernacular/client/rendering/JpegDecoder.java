package com.shinyhut.vernacular.client.rendering;

import java.io.IOException;

/**
 * Decodes JPEG image data into raw pixel arrays. Platform-specific implementations
 * should be provided via {@link com.shinyhut.vernacular.client.VernacularConfig#setJpegDecoder(JpegDecoder)}.
 * <p>
 * On desktop (AWT), use {@code ImageIO.read()} + {@code BufferedImage.getRGB()}.
 * On Android, use {@code BitmapFactory.decodeByteArray()}.
 */
@FunctionalInterface
public interface JpegDecoder {

    /**
     * Decodes JPEG data into a row-major pixel array.
     *
     * @param jpegData the raw JPEG bytes
     * @param width    expected image width in pixels
     * @param height   expected image height in pixels
     * @return row-major {@code int[]} of length {@code width * height},
     *         each element packed as {@code 0x00RRGGBB}
     * @throws IOException if the JPEG data cannot be decoded
     */
    int[] decode(byte[] jpegData, int width, int height) throws IOException;
}
