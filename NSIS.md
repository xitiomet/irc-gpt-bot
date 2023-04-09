## Building an NSIS Package for app deployment

In order to build an NSIS package you will need to add a "jre" folder to your project directory. This runtime environment will be deployed with your installer.

The program jlink was designed to do this. Here is an example script
```
@echo off
jlink.exe --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.security.jgss,java.xml --output .\jre --strip-debug --compress 2 --no-header-files --no-man-pages
```

This has also been included in your project directory as "getJRE.cmd"

### MakeNSIS ###

Another requirement is an installation of NSIS itself you can direct your pom.xml to the correct installation by editing the following lines in your pom.xml

```xml
<makensisExecutable>C:\Program Files (x86)\NSIS\makensis.exe</makensisExecutable>
<makensisExecutableLinux>/usr/bin/makensis</makensisExecutableLinux>
<makensisExecutableMacOS></makensisExecutableMacOS>
```

See More here https://sourceforge.net/projects/nsis/