## Building the Windows Installer

On Windows, `mvn package` activates the `windows` profile. It builds a GraalVM native executable (`target/irc-gpt-bot.exe`) and wraps it in an NSIS installer at `target/IrcGPTBotSetup-<version>.exe`. No Java runtime is bundled or needed.

### Requirements

- A GraalVM JDK (25 or newer) with `JAVA_HOME` or `GRAALVM_HOME` pointing at it
- Visual Studio (or the Build Tools) with the "Desktop development with C++" workload. Run the build from an **x64 Native Tools Command Prompt** so `cl.exe`, `link.exe` and `rc.exe` are on the `PATH` and `VCToolsRedistDir` is set.
- [NSIS](https://nsis.sourceforge.io/). Its default install location is detected; it does not need to be on the `PATH`.

### Build

```
mvn package
```

The build does the following:

1. Fills in [src/windows/irc-gpt-bot.rc](src/windows/irc-gpt-bot.rc) with the project version and compiles it with `rc.exe`, which embeds the icon and version info in the executable.
2. Builds the native image as a Windows GUI application. There is no console window: started without `-h`, it opens its Swing terminal window.
3. Copies the JDK's AWT font configuration to `target/lib`. GUI mode needs it next to the executable.
4. Runs [src/windows/Build-Installer.ps1](src/windows/Build-Installer.ps1). It stages the executable, the native DLLs from `target`, the Visual C++ runtime DLLs and `lib/`, then compiles [src/windows/irc-gpt-bot.nsi](src/windows/irc-gpt-bot.nsi).

### Options

| Property | Default | Purpose |
|----------|---------|---------|
| `-Dwindows.makeNSIS=<path>` | `makensis.exe` | Path to `makensis.exe`. If it is not on the `PATH`, the build looks for it in the NSIS install location from the registry, then in `Program Files (x86)\NSIS` and `Program Files\NSIS`. |
| `-Dwindows.vcRuntimeDir=<dir>` | `auto` | x64 Visual C++ CRT folder (containing `vcruntime140.dll`). `auto` detects it from `VCToolsRedistDir`. |
| `-DskipNative=true` | `false` | Build only the jar. This skips the native executable and the installer. |

The installer puts the program under `Program Files\IRCGPTBot`, adds a Start Menu shortcut (and optionally a desktop shortcut), and registers an uninstaller. Configuration is still read from `%USERPROFILE%\.irc-gpt-bot.json`.
