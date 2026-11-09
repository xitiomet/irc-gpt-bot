;IRC GPT Bot installer (GraalVM native image)
;Built by Build-Installer.ps1, which stages the payload and passes the defines below.

Unicode true
!include "MUI2.nsh"
!include "x64.nsh"
!include "nsDialogs.nsh"

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

  Var OpenAIKey
  Var OpenAIKeyField

;--------------------------------
;Interface Settings

  !define MUI_ABORTWARNING
  !define MUI_ICON "..\main\resources\win.ico"
  !define MUI_UNICON "..\main\resources\win.ico"

;--------------------------------
;Pages

  !insertmacro MUI_PAGE_COMPONENTS
  !insertmacro MUI_PAGE_DIRECTORY
  Page custom OpenAIKeyPage OpenAIKeyPageLeave
  !insertmacro MUI_PAGE_INSTFILES
  !define MUI_FINISHPAGE_RUN
  !define MUI_FINISHPAGE_RUN_TEXT "Launch IRC GPT Bot"
  !define MUI_FINISHPAGE_RUN_FUNCTION LaunchBot
  !insertmacro MUI_PAGE_FINISH

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

Function OpenAIKeyPage
  IfFileExists "$PROFILE\.irc-gpt-bot\*.*" existing_config
  IfFileExists "$PROFILE\.irc-gpt-bot.json" existing_config

  !insertmacro MUI_HEADER_TEXT "OpenAI API key" "Set up IRC GPT Bot before its first launch."
  nsDialogs::Create 1018
  Pop $0
  ${If} $0 == error
    MessageBox MB_ICONSTOP "Unable to display the OpenAI API key page."
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 36u "Paste your OpenAI API key below (Ctrl+V). It will be saved in your user configuration, not in the program folder. Leave it blank to configure it later."
  Pop $0
  ${NSD_CreateLabel} 0 44u 100% 12u "OpenAI API key:"
  Pop $0
  ${NSD_CreatePassword} 0 60u 100% 14u "$OpenAIKey"
  Pop $OpenAIKeyField
  IntOp $0 ${NSIS_MAX_STRLEN} - 1
  SendMessage $OpenAIKeyField ${EM_LIMITTEXT} $0 0
  nsDialogs::Show
  Return

existing_config:
  StrCpy $OpenAIKey ""
  Abort
FunctionEnd

Function OpenAIKeyPageLeave
  ${NSD_GetText} $OpenAIKeyField $OpenAIKey
FunctionEnd

Function SaveInitialConfiguration
  StrCmp $OpenAIKey "" done
  ;Recheck after the page, so an existing configuration is never replaced.
  IfFileExists "$PROFILE\.irc-gpt-bot\*.*" done
  IfFileExists "$PROFILE\.irc-gpt-bot.json" done
  ClearErrors
  CreateDirectory "$PROFILE\.irc-gpt-bot"
  IfErrors failed
  FileOpen $0 "$PROFILE\.irc-gpt-bot\irc-gpt-bot.json" w
  IfErrors failed
  FileWrite $0 '{$\r$\n  "openAiKey": "'
  IfErrors write_failed
  StrCpy $1 0
  ${Do}
    StrCpy $2 $OpenAIKey 1 $1
    StrCmp $2 "" end_key
    ;Escape UTF-16 code units as ASCII JSON, including quotes and control characters.
    System::Call '*(&w1 r2) p.r3'
    System::Call '*$3(&i2 .r4)'
    System::Free $3
    IntFmt $2 '\u%04x' $4
    FileWrite $0 $2
    IfErrors write_failed
    IntOp $1 $1 + 1
  ${Loop}
end_key:
  FileWrite $0 '"$\r$\n}$\r$\n'
  IfErrors write_failed
  FileClose $0
  IfErrors close_failed
done:
  StrCpy $OpenAIKey ""
  Return

write_failed:
  FileClose $0
close_failed:
  Delete "$PROFILE\.irc-gpt-bot\irc-gpt-bot.json"
failed:
  RMDir "$PROFILE\.irc-gpt-bot"
  StrCpy $OpenAIKey ""
  MessageBox MB_ICONSTOP "Unable to save the OpenAI API key in $PROFILE\.irc-gpt-bot. Check folder permissions and available disk space, then retry."
  Abort
FunctionEnd

Function LaunchBot
  SetOutPath "$INSTDIR"
  ClearErrors
  ExecShell "open" "$INSTDIR\${IMAGE_NAME}.exe" "-g"
  ${If} ${Errors}
    MessageBox MB_ICONSTOP "Unable to launch IRC GPT Bot. Try the Start Menu shortcut."
  ${EndIf}
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
  Call SaveInitialConfiguration
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
