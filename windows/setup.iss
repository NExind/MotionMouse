[Setup]
AppName=Motion Mouse
AppVersion=1.0
DefaultDirName={autopf}\Motion Mouse
DefaultGroupName=Motion Mouse
OutputBaseFilename=MotionMouseSetup
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=MotionMouse\motion_mouse.ico

[Files]
; Grab everything from the publish folder (all the DLLs, localization folders, and the EXE)
Source: "MotionMouse\bin\Release\net9.0-windows10.0.17763.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Start Menu Icon
Name: "{group}\Motion Mouse"; Filename: "{app}\MotionMouse.exe"
; Desktop Icon
Name: "{commondesktop}\Motion Mouse"; Filename: "{app}\MotionMouse.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
; Optionally run the app after installation
Filename: "{app}\MotionMouse.exe"; Description: "Launch Motion Mouse"; Flags: nowait postinstall skipifsilent
