package org.openstatic;

import java.io.IOException;
import java.net.URI;

// Opens URLs with the platform's native launcher so the terminal UI never loads AWT.
final class BrowserLauncher
{
    private BrowserLauncher() {}

    static void open(URI address) throws IOException
    {
        Process launcher = new ProcessBuilder(command(System.getProperty("os.name", ""), address))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try
        {
            int status = launcher.waitFor();
            if (status != 0)
                throw new IOException("Browser launcher exited with status " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Browser launch interrupted", e);
        }
    }

    static String[] command(String osName, URI address)
    {
        if (osName.startsWith("Windows"))
            return new String[] {"rundll32", "url.dll,FileProtocolHandler", address.toString()};
        if (osName.startsWith("Mac"))
            return new String[] {"open", address.toString()};
        return new String[] {"xdg-open", address.toString()};
    }
}
