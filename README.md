# OverlayNotes

A lightweight, always-on-top glass notes overlay for Windows. Type quick notes over any app, and they stay visible only to you — the window is excluded from screen shares and hidden from the taskbar.

## Features

- **Glass overlay** — translucent tinted window that floats on top of everything
- **Dark / light mode** — header toggle (or Ctrl+T), remembered between runs
- **Adjustable glass tint** — slider (5–95) controls how much of the background shows through
- **Adjustable font size** — type a size 10–48 + Enter, applied live
- **Autosave** — edits save automatically shortly after you stop typing; close = save
- **Single instance** — a second launch just tells you the app is already running
- **Capture exclusion** — hidden from screen sharing and recordings (Windows 10 2004+)
- **Hidden from taskbar** — no taskbar icon; drag anywhere, resize from the corner

## Run the app

### Option 1 — download the ready exe (recommended, no setup)

1. Go to the [**Releases**](https://github.com/SanjayBaba420/OverLay/releases) page of this repo.
2. Download `OverlayNotes-windows.zip` from the latest release.
3. Unzip it anywhere (e.g. Desktop).
4. Open the unzipped `OverlayNotes` folder and double-click `OverlayNotes.exe`.

No Java installation needed — the runtime is bundled. Keep the folder together:
the `app\` and `runtime\` folders must stay beside the executable.

Notes are saved to `overlay_notes.txt` **next to the .exe**.

### Option 2 — build from source (Windows, for developers)

Requirements: Windows 10 (build 19041+) or Windows 11, Java 17+, Maven.

## Build from source (Windows)

```powershell
# compile + package the jar
mvn package

# create the self-contained Windows app image (bundled Java runtime)
& "$env:JAVA_HOME\bin\jpackage.exe" --type app-image --name OverlayNotes --app-version 1.0.0 `
  --input .\target\windows-package-input `
  --main-jar OverlayNotes-1.0-SNAPSHOT.jar `
  --main-class org.OverlayNotes.Main `
  --dest .\dist\native `
  --java-options '--enable-native-access=ALL-UNNAMED' `
  --add-modules java.desktop,java.logging,jdk.unsupported
```

Before running jpackage, prepare the input folder:

```powershell
New-Item -ItemType Directory -Path .\target\windows-package-input -Force
Copy-Item .\target\OverlayNotes-1.0-SNAPSHOT.jar .\target\windows-package-input\
Copy-Item .\target\dependency\*.jar .\target\windows-package-input\
```

Or use the helper script (requires PowerShell script execution enabled):

```powershell
.\package-windows.ps1 -JdkHome "$env:JAVA_HOME"
```

## Run from source (development)

```powershell
mvn package
java --enable-native-access=ALL-UNNAMED -cp ".\target\classes;.\target\dependency\*" org.OverlayNotes.Main
```

## Project layout

```
src/main/java/org/OverlayNotes/Main.java            # UI, note editing, autosave
src/main/java/org/OverlayNotes/CapturePrivacy.java  # Windows capture-exclusion (JNA)
pom.xml                        # Maven build (Java 17, JNA dependency)
package-windows.ps1            # Helper to build the packaged exe
dist/native/OverlayNotes/      # Ready-to-run Windows app
```

## Notes on privacy

- Uses `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` so the window is skipped by most capture APIs (OBS, Teams, Zoom, browser share).
- If exclusion fails (unsupported Windows build, policy restriction), the app **refuses to open** rather than risk showing your notes.
- Local screen-recording tools using some hardware paths may still capture the window — always verify with a test share first.
- Residual risks (by design, not bugs): notes are stored as plaintext next to the exe; copy/paste puts text in the Windows clipboard (history/cloud sync can retain it); a phone photo of the screen obviously still works.

## Tips

- Drag the top bar to move; drag the bottom-right corner to resize.
- Close = autosave. No confirmation prompt.
- Font size, glass tint, and theme persist in `.overlay_notes_settings` next to your notes.
