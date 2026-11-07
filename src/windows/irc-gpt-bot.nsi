;IRC GPT Bot installer (GraalVM native image)
;Built by Build-Installer.ps1, which stages the payload and passes the defines below.

Unicode true
!include "MUI2.nsh"
!include "x64.nsh"

!ifndef VERSION
!define VERSION "1.0"
!endif
!ifndef IMAGE_NAME
!define IMAGE_NAME "irc-gpt-bot"
!endif
!ifndef PAYLOAD_DIR
!define PAYLOAD_DIR "..\..\target\windows-installer\payload"
!endif
!ifndef OUTPUT_FILE
!define OUTPUT_FILE "..\..\target\IrcGPTBotSetup-${VERSION}.exe"
!endif

;--------------------------------
;General

  Name "IRC GPT Bot"
  OutFile "${OUTPUT_FILE}"
  InstallDir "$PROGRAMFILES64\IRCGPTBot"
  InstallDirRegKey HKLM "Software\IRCGPTBot" ""
  RequestExecutionLevel admin
  SetCompressor /SOLID lzma
  ShowInstDetails show
  ShowUninstDetails show

  !define REG_UNINSTALL "Software\Microsoft\Windows\CurrentVersion\Uninstall\IRCGPTBot"

;--------------------------------
;Interface Settings

  !define MUI_ABORTWARNING
  !define MUI_ICON "..\main\resources\win.ico"
  !define MUI_UNICON "..\main\resources\win.ico"

;--------------------------------
;Pages

  !insertmacro MUI_PAGE_COMPONENTS
  !insertmacro MUI_PAGE_DIRECTORY
  !insertmacro MUI_PAGE_INSTFILES

  !insertmacro MUI_UNPAGE_CONFIRM
  !insertmacro MUI_UNPAGE_INSTFILES

;--------------------------------
;Languages

  !insertmacro MUI_LANGUAGE "English"

;--------------------------------
;Functions

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "IRC GPT Bot requires 64-bit Windows."
    Abort
  ${EndIf}
  SetRegView 64
FunctionEnd

Function un.onInit
  SetRegView 64
FunctionEnd

;--------------------------------
;Installer Sections

Section "IRC GPT Bot" Main
SectionIn RO
  SetOutPath "$INSTDIR"
  ClearErrors
  File "${PAYLOAD_DIR}\${IMAGE_NAME}.exe"
  File "${PAYLOAD_DIR}\*.dll"
  SetOutPath "$INSTDIR\lib"
  File /nonfatal /r "${PAYLOAD_DIR}\lib\*"
  SetOutPath "$INSTDIR"
  IfErrors 0 +3
    MessageBox MB_ICONSTOP "Unable to install IRC GPT Bot files. Close any running copies of IRC GPT Bot and retry."
    Abort

  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayName" "IRC GPT Bot"
  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayIcon" "$INSTDIR\${IMAGE_NAME}.exe"
  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${REG_UNINSTALL}" "Publisher" "openstatic.org"
  WriteRegStr HKLM "${REG_UNINSTALL}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${REG_UNINSTALL}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${REG_UNINSTALL}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWord HKLM "${REG_UNINSTALL}" "NoModify" 1
  WriteRegDWord HKLM "${REG_UNINSTALL}" "NoRepair" 1

  ;Store installation folder
  WriteRegStr HKLM "Software\IRCGPTBot" "" $INSTDIR

  ;Create uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Start Menu Shortcut" StartMenu
  CreateShortcut "$SMPROGRAMS\IRC GPT Bot.lnk" "$INSTDIR\${IMAGE_NAME}.exe" "-g" "$INSTDIR\${IMAGE_NAME}.exe" 0
SectionEnd

Section /o "Desktop Shortcut" Desktop
  CreateShortcut "$DESKTOP\IRC GPT Bot.lnk" "$INSTDIR\${IMAGE_NAME}.exe" "-g" "$INSTDIR\${IMAGE_NAME}.exe" 0
SectionEnd

;--------------------------------
;Uninstaller Section

Section "Uninstall"
  Delete "$SMPROGRAMS\IRC GPT Bot.lnk"
  Delete "$DESKTOP\IRC GPT Bot.lnk"
  ;Remove the old launch4j-based install's shortcut as well
  Delete "$SMPROGRAMS\IRCGPTBot.lnk"
  RMDir /r "$INSTDIR"

  DeleteRegKey HKLM "Software\IRCGPTBot"
  DeleteRegKey HKLM "${REG_UNINSTALL}"
SectionEnd
