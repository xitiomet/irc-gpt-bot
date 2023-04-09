;NSIS Modern User Interface
;Basic Example Script
;Written by Joost Verburg

;--------------------------------
;Include Modern UI

  !include "MUI2.nsh"

;--------------------------------
;General

  ;Name and file
  Name "IRCGPTBot"

  ;Default installation folder
  InstallDir "$PROGRAMFILES\IRCGPTBot"
  
  ;Get installation folder from registry if available
  InstallDirRegKey HKCU "Software\IRCGPTBot" ""

  ;Request application privileges for Windows Vista
  RequestExecutionLevel admin
  
  !define REG_UNINSTALL "Software\Microsoft\Windows\CurrentVersion\Uninstall\IRCGPTBot"

;--------------------------------
;Interface Settings

  !define MUI_ABORTWARNING

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
;Installer Sections

Section "IRCGPTBot" Main
SectionIn RO
  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayName" "IRC GPT Bot"
  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayIcon" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "${REG_UNINSTALL}" "DisplayVersion" "1.0"
  WriteRegStr HKLM "${REG_UNINSTALL}" "Publisher" "openstatic.org"
  WriteRegStr HKLM "${REG_UNINSTALL}" "InstallSource" "$EXEDIR\"
 
  ;Under WinXP this creates two separate buttons: "Modify" and "Remove".
  ;"Modify" will run installer and "Remove" will run uninstaller.
  WriteRegDWord HKLM "${REG_UNINSTALL}" "NoModify" 1
  WriteRegDWord HKLM "${REG_UNINSTALL}" "NoRepair" 0
  WriteRegStr HKLM "${REG_UNINSTALL}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  
  SetOutPath "$INSTDIR"
  
  File ${PROJECT_BUILD_DIR}\irc-gpt-bot.exe
  CreateShortcut "$SMPROGRAMS\IRCGPTBot.lnk" "$INSTDIR\irc-gpt-bot.exe"

  ;Store installation folder
  WriteRegStr HKCU "Software\IRCGPTBot" "" $INSTDIR
  
  ;Create uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"

SectionEnd


Section "Java Runtime Environment" java
  SetOutPath "$INSTDIR\jre"
  File /r "${PROJECT_BASEDIR}\jre\*"
SectionEnd

;--------------------------------
;Uninstaller Section

Section "Uninstall"

  ;ADD YOUR OWN FILES HERE...

  Delete "$INSTDIR\Uninstall.exe"
  Delete "$INSTDIR\irc-gpt-bot.exe"
  Delete "$SMPROGRAMS\IRCGPTBot.lnk"
  RMDir /r "$INSTDIR"

  DeleteRegKey /ifempty HKCU "Software\IRCGPTBot"
  DeleteRegKey HKLM "${REG_UNINSTALL}"
SectionEnd
