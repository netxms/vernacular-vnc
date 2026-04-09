# Vernacular VNC
[![Latest release](https://img.shields.io/github/release/shinyhut/vernacular-vnc.svg)](https://github.com/shinyhut/vernacular-vnc/releases/latest)
[![Build Status](https://app.travis-ci.com/shinyhut/vernacular-vnc.svg?branch=master)](https://app.travis-ci.com/shinyhut/vernacular-vnc)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Vernacular is a pure Java VNC remote desktop client library. Vernacular is open-source and
released under the MIT license.

## Getting Started

The latest releases are available for manual download from our [releases page](https://github.com/shinyhut/vernacular-vnc/releases).
Vernacular is also available through Maven:

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>org.netxms</groupId>
        <artifactId>vernacular-vnc</artifactId>
        <version>1.18</version>
    </dependency>
</dependencies>
```

### Gradle
```groovy
repositories {
    mavenCentral()
}

dependencies {
    compile 'org.netxms:vernacular-vnc:1.18'
}
```

## Upgrading to 1.18

Version 1.18 removes all `java.awt` dependencies from the core library, making it usable on
Android and other non-AWT platforms. This introduces two breaking changes:

- `VernacularClient.handleKeyEvent(KeyEvent)` has been removed. Use `updateKey(int keySym, boolean pressed)` or `type(int keySym)` / `type(String text)` instead. Callers that need to translate AWT `KeyEvent` objects to X11 keysyms can use `KeySyms.map(keyCode, keyChar, shiftDown)`.

- If the VNC server uses Tight encoding with JPEG sub-rectangles, you must now provide a `JpegDecoder` via `config.setJpegDecoder(...)`. Without it, JPEG sub-rectangles will throw an exception. On desktop (AWT) platforms:

```java
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

config.setJpegDecoder((data, w, h) -> {
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
    if (img == null) {
        throw new IOException("Failed to decode JPEG data");
    }
    int[] pixels = new int[w * h];
    img.getRGB(0, 0, w, h, pixels, 0, w);
    return pixels;
});
```

On Android, use `BitmapFactory` instead of `ImageIO`.

## Usage

The Vernacular .jar file is executable, and it can be used as a simple but functional VNC client. However, Vernacular
is primarily intended to be used as a library in third-party applications: 

```java
package com.shinyhut.vernacular;

import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import com.shinyhut.vernacular.client.rendering.ImageBuffer;

public class VernacularDemo {

    public static void main(String[] args) {

        VernacularConfig config = new VernacularConfig();
        VernacularClient client = new VernacularClient(config);

        // Select 8-bits per pixel indexed color, or 8/16/24 bits per pixel true color
        config.setColorDepth(ColorDepth.BPP_8_INDEXED);

        // Set up callbacks for the various events that can happen in a VNC session

        // Exception handler
        config.setErrorListener(Throwable::printStackTrace);

        // Password supplier - this is only invoked if the remote server requires authentication
        config.setPasswordSupplier(() -> "my secret password");

        // Handle system bell events from the remote host
        config.setBellListener(v -> System.out.println("DING!"));

        // Receive content copied to the remote clipboard
        config.setRemoteClipboardListener(text -> System.out.println(String.format("Received copied text: %s", text)));

        // Receive screen updates from the remote host
        // Expect this event to be triggered several times per second
        config.setScreenUpdateListener(image -> {
            int width = image.getWidth();
            int height = image.getHeight();
            System.out.println(String.format("Received a %dx%d screen update", width, height));
        });

        try {
            // Start the VNC session
            client.start("localhost", 5900);

            // Move the mouse
            client.moveMouse(400, 300);

            // Click a mouse button. Buttons are numbered 1 - 3
            client.click(1);

            // Type some text.
            client.type("Hello world!");

            // Copy some text to the remote clipboard
            client.copyText("Hello from the VNC client!");

            // Let the VNC session continue as long as required
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ignored) {
            }
        } finally {
            // Terminate the VNC session and cleanup
            client.stop();
        }
    }
}
```

For a more realistic example, see [Vernacular Viewer](viewer/src/main/java/com/netxms/vernacular/VernacularViewer.java) in the source distribution, which demonstrates how to use Vernacular to build a working remote desktop application.
