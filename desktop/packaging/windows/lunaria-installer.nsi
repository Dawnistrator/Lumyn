; Lunaria for Windows — Per-User NSIS Modern UI 2 Installer
; Installs cleanly into %LOCALAPPDATA%\Programs\Lunaria without UAC elevation,
; registers audio/playlist file associations, Start Menu shortcut, and Uninstaller.

Unicode True
!include "MUI2.nsh"

!ifndef VERSION
  !define VERSION "1.0.0"
!endif
!ifndef OUT_FILE
  !define OUT_FILE "Lunaria-windows-x64-setup.exe"
!endif
!ifndef EXE_SOURCE
  !define EXE_SOURCE "..\..\target\release\lunaria-desktop.exe"
!endif

Name "Lunaria"
OutFile "${OUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\Lunaria"
InstallDirRegKey HKCU "Software\Lunaria" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!define MUI_ABORTWARNING
!define MUI_ICON "..\..\apps\lunaria-desktop\assets\lunaria.ico"
!define MUI_UNICON "..\..\apps\lunaria-desktop\assets\lunaria.ico"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "..\..\..\LICENSE"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\lunaria.exe"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Section "Lunaria Desktop Player" SecMain
  SetShellVarContext current
  SetOutPath "$INSTDIR"

  File "/oname=lunaria.exe" "${EXE_SOURCE}"
  File "..\..\..\LICENSE"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; Remember install location
  WriteRegStr HKCU "Software\Lunaria" "InstallDir" "$INSTDIR"

  ; Start Menu & Desktop Shortcuts
  CreateDirectory "$SMPROGRAMS\Lunaria"
  CreateShortcut "$SMPROGRAMS\Lunaria\Lunaria.lnk" "$INSTDIR\lunaria.exe" "" "$INSTDIR\lunaria.exe" 0
  CreateShortcut "$SMPROGRAMS\Lunaria\Uninstall Lunaria.lnk" "$INSTDIR\Uninstall.exe" "" "$INSTDIR\Uninstall.exe" 0
  CreateShortcut "$DESKTOP\Lunaria.lnk" "$INSTDIR\lunaria.exe" "" "$INSTDIR\lunaria.exe" 0

  ; Add/Remove Programs Entry
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "DisplayName" "Lunaria (Hi-Res Local Audio Player)"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "Publisher" "Lunaria Audio"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "DisplayIcon" "$INSTDIR\lunaria.exe,0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria" "NoRepair" 1

  ; Per-user ProgID & Explorer Open-With Associations
  WriteRegStr HKCU "Software\Classes\Lunaria.AudioFile" "" "Lunaria Audio File"
  WriteRegStr HKCU "Software\Classes\Lunaria.AudioFile\DefaultIcon" "" "$INSTDIR\lunaria.exe,0"
  WriteRegStr HKCU "Software\Classes\Lunaria.AudioFile\shell\open\command" "" '"$INSTDIR\lunaria.exe" "%1"'

  !macro REGISTER_EXT EXT
    WriteRegStr HKCU "Software\Classes\${EXT}\OpenWithProgids" "Lunaria.AudioFile" ""
  !macroend

  !insertmacro REGISTER_EXT ".flac"
  !insertmacro REGISTER_EXT ".wav"
  !insertmacro REGISTER_EXT ".alac"
  !insertmacro REGISTER_EXT ".mp3"
  !insertmacro REGISTER_EXT ".m4a"
  !insertmacro REGISTER_EXT ".aac"
  !insertmacro REGISTER_EXT ".ogg"
  !insertmacro REGISTER_EXT ".aiff"
  !insertmacro REGISTER_EXT ".aif"
  !insertmacro REGISTER_EXT ".cue"
  !insertmacro REGISTER_EXT ".m3u"
  !insertmacro REGISTER_EXT ".m3u8"

  ; Refresh Windows Explorer file icon / Open-With associations cache
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Section "Uninstall"
  SetShellVarContext current

  Delete "$DESKTOP\Lunaria.lnk"
  Delete "$SMPROGRAMS\Lunaria\Lunaria.lnk"
  Delete "$SMPROGRAMS\Lunaria\Uninstall Lunaria.lnk"
  RMDir "$SMPROGRAMS\Lunaria"

  Delete "$INSTDIR\lunaria.exe"
  Delete "$INSTDIR\LICENSE"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\Lunaria"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Lunaria"
  DeleteRegKey HKCU "Software\Classes\Lunaria.AudioFile"

  !macro UNREGISTER_EXT EXT
    DeleteRegValue HKCU "Software\Classes\${EXT}\OpenWithProgids" "Lunaria.AudioFile"
  !macroend

  !insertmacro UNREGISTER_EXT ".flac"
  !insertmacro UNREGISTER_EXT ".wav"
  !insertmacro UNREGISTER_EXT ".alac"
  !insertmacro UNREGISTER_EXT ".mp3"
  !insertmacro UNREGISTER_EXT ".m4a"
  !insertmacro UNREGISTER_EXT ".aac"
  !insertmacro UNREGISTER_EXT ".ogg"
  !insertmacro UNREGISTER_EXT ".aiff"
  !insertmacro UNREGISTER_EXT ".aif"
  !insertmacro UNREGISTER_EXT ".cue"
  !insertmacro UNREGISTER_EXT ".m3u"
  !insertmacro UNREGISTER_EXT ".m3u8"

  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
