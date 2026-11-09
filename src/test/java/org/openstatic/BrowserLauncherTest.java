package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

class BrowserLauncherTest
{
    private final URI address = URI.create("http://127.0.0.1:6553/");

    @Test
    void usesNativeLauncherPerPlatform()
    {
        assertArrayEquals(new String[] {"rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:6553/"},
            BrowserLauncher.command("Windows 11", address));
        assertArrayEquals(new String[] {"open", "http://127.0.0.1:6553/"}, BrowserLauncher.command("Mac OS X", address));
        assertArrayEquals(new String[] {"xdg-open", "http://127.0.0.1:6553/"}, BrowserLauncher.command("Linux", address));
    }
}
