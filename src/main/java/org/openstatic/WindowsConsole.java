package org.openstatic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.ProcessProperties;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.PointerBase;

/**
 * Native image startup fixes. Sets java.home so AWT can find its font configuration, and on Windows,
 * where the image is linked as a GUI application (no console window), attaches to the console it was
 * started from so --help and headless logging are visible. Only loaded inside a native image (see IRCGPTBotMain.main).
 */
final class WindowsConsole
{
    private static final int ATTACH_PARENT_PROCESS = -1;
    private static final int STD_OUTPUT_HANDLE = -11;

    private WindowsConsole() {}

    @CFunction(value = "AttachConsole", transition = CFunction.Transition.NO_TRANSITION)
    private static native int attachConsole(int processId);

    @CFunction(value = "GetStdHandle", transition = CFunction.Transition.NO_TRANSITION)
    private static native PointerBase getStdHandle(int stdHandle);

    static void attachParentConsole()
    {
        // AWT loads its font configuration from ${java.home}/lib; the build stages that lib/ folder next to the executable
        if (System.getProperty("java.home") == null)
        {
            String executable = ProcessProperties.getExecutableName();
            if (executable != null)
                System.setProperty("java.home", new File(executable).getAbsoluteFile().getParent());
        }
        // Folded at image build time, so the Win32 calls are never linked into non-Windows images
        if (!Platform.includedIn(Platform.WINDOWS.class))
            return;
        // Output already redirected to a file or pipe works as-is
        if (getStdHandle(STD_OUTPUT_HANDLE).isNonNull())
            return;
        if (attachConsole(ATTACH_PARENT_PROCESS) == 0)
            return;
        try
        {
            System.setOut(new PrintStream(new FileOutputStream("CONOUT$"), true));
            System.setErr(new PrintStream(new FileOutputStream("CONOUT$"), true));
            System.out.println();
        } catch (Exception e) {}
    }
}
