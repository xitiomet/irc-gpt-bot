@echo off
jlink --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,java.sql,java.xml,org.graalvm.nativeimage.llvm,org.graalvm.truffle --output .\jre --strip-debug --compress 2 --no-header-files --no-man-pages
pause
