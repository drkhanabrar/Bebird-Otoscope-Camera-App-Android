# CARE ENT Scope Camera

Professional viewer, capture and recording workstation for the Wi-Fi ENT
endoscope camera used in the CARE ENT Scope workflow.

Developed by **Dr. Abrar Khan** — Care Hospital, Chikhli
Support: +91 9370111449 · www.carehospital.in

| | |
|---|---|
| **Windows app** | Python + Tkinter + OpenCV, builds to a single `.exe` |
| **Android app** | Kotlin, builds to an installable `.apk` |
| **Camera** | Bebird-type Wi-Fi ENT scope, UDP/JPEG, default `192.168.10.123:8030` |

---

## What's in this repository

```
care-ent-scope-camera/
├── windows/                       The Windows workstation
│   ├── care_ent_scope_camera.py   The whole application (one file)
│   ├── requirements.txt           Libraries it needs
│   └── build_exe.bat              Double-click to build the .exe yourself
│
├── android/                       The Android app (open this in Android Studio)
│   └── app/src/main/
│       ├── java/com/carehospital/entscope/
│       │   ├── MainActivity.kt    Screen, buttons, full screen, capture
│       │   ├── ScopeStream.kt     UDP receiver for the camera
│       │   ├── ScopeView.kt       Live video with zoom, pan and overlays
│       │   ├── VideoRecorder.kt   H.264 / MP4 recording
│       │   ├── MediaSaver.kt      Saves photos and videos to the gallery
│       │   └── AboutActivity.kt   About page
│       └── res/                   Layouts, colours, icon
│
└── .github/workflows/             GitHub builds both apps for you, free
    ├── android.yml                → produces the .apk
    └── windows.yml                → produces the .exe
```

---

## Version 3.0.0 — what changed

Four problems reported in version 2.0.0 are fixed, and the whole interface was
rebuilt to look and feel more professional.

### 1. Buttons no longer flicker when the cursor is over them

The old buttons were a `Frame` with a `Label` sitting inside it. Tk sends a
`<Leave>` event to the frame the instant the pointer crosses onto its child
label, followed immediately by an `<Enter>` — so the button repainted over and
over, which is what you saw as flicker.

Every button is now a **single `Canvas` widget with no child widgets at all**
(the `RoundButton` class). The shape and the caption are canvas *items*, which
never generate crossing events, so hovering fires exactly once and only
recolours what is already drawn. Verified: moving on and off a button 40 times
triggers **zero** repaints of the button.

### 2. "Connect Camera" disappears once video starts

The welcome panel now follows one rule: it is on screen **only while there is
no picture**. The first decoded frame hides it; disconnecting or losing the
stream brings it back. The button in the command bar also flips between
**Connect** and **Disconnect** so the current state is always obvious.

### 3. Full Screen shows only the video

Previously `-fullscreen` was applied to the main window, so the header, command
bar and footer went full screen along with the picture. Now **F11**, the
**Full Screen** button, or a **double-click on the video** opens a separate
black window containing nothing but the video, and the render loop draws into
that window while it is open. **Esc**, **F11** or a double-click closes it.

### 4. Professional, elegant look with rounded buttons

Rounded pill buttons, rounded panels and status badges, a refined clinical dark
palette and Segoe UI typography throughout. The Control Center panel has a
**scrollbar on the side and a horizontal scrollbar**, plus mouse-wheel support
(Shift + wheel scrolls sideways), so nothing in it is ever clipped.

---

## Installing the Windows app

### The easy way — let GitHub build it

After you push this repository to GitHub (steps further down), open the
**Actions** tab → **Build Windows EXE** → **Run workflow**. When it finishes,
download **CARE-ENT-Scope-Camera-Windows** from the Artifacts section. Inside is
`CARE_ENT_Scope_Camera.exe` — copy it anywhere and double-click it.

### Building it on your own PC

1. Install **Python 3.12** from python.org and tick **Add Python to PATH**.
2. Double-click `windows/build_exe.bat`.
3. The finished program appears in the `dist` folder.

### Running it without building

```
pip install -r windows/requirements.txt
python windows/care_ent_scope_camera.py
```

### Keyboard shortcuts

| Key | Action |
|---|---|
| `Ctrl` + `S` | Save a snapshot |
| `Ctrl` + `R` | Start / stop recording |
| `Space` | Freeze / resume the display |
| `F11` | Video-only full screen |
| `Esc` | Leave full screen, or close the Control Center |
| `+` / `−` | Zoom in / out |
| Mouse wheel | Zoom · drag to pan · double-click for full screen |

Snapshots go to `Documents\CARE ENT Scope\Captures`, recordings to
`Documents\CARE ENT Scope\Videos`.

---

## Installing the Android app

### The easy way — let GitHub build the APK

1. Push this repository to GitHub (steps below).
2. Open the **Actions** tab → **Build Android APK** → **Run workflow**.
3. Wait about three minutes for the green tick.
4. Open the finished run and download **CARE-ENT-Scope-Camera-APK**.
5. Unzip it and copy **`CARE-ENT-Scope-Camera-debug.apk`** to the phone.
6. Tap it. Android will ask permission to install apps from this source — allow
   it, then tap Install.

> Install the file ending in **`-debug.apk`**. The `release-unsigned` one cannot
> be installed until it is signed with your own key (see *Signing* below).

### Building it in Android Studio

1. Install **Android Studio** (Koala or newer).
2. **File → Open** and choose the **`android`** folder — not the top folder.
3. Wait for Gradle to finish downloading (first time only, a few minutes).
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Click **locate** in the notification to find the APK.

### Using the app

1. On the phone, open **Settings → Wi-Fi** and join the **scope's Wi-Fi
   network** (the camera creates its own network).
2. **Turn mobile data off.** This matters — see the note below.
3. Open **CARE ENT Scope** and tap **Connect Camera**.

> **Why mobile data matters.** An Android phone will happily send the app's
> network traffic over mobile data, because the scope's Wi-Fi has no internet.
> The app asks Android to bind its connection to the Wi-Fi network specifically,
> which handles this on most phones, but turning mobile data off removes all
> doubt.

Photos and videos are saved into the phone's own gallery, under
**Pictures/CARE ENT Scope** and **Movies/CARE ENT Scope**.

### Gestures

| Gesture | Action |
|---|---|
| Pinch | Zoom |
| Drag (while zoomed) | Pan |
| Double-tap | Video-only full screen |
| Back | Leave full screen |

---

## Putting this on GitHub

You only do steps 1–3 once.

### 1. Make an empty repository

Go to <https://github.com/new>, name it `care-ent-scope-camera`, choose
**Private**, and **do not** tick "Add a README" — this project already has one.
Press **Create repository**.

### 2. Install Git

Download it from <https://git-scm.com/download/win> and accept every default.

### 3. Upload the project

Unzip this project somewhere, open the folder, right-click inside it and choose
**Open in Terminal** (or **Git Bash Here**). Then type these lines one at a
time, replacing `YOUR-USERNAME`:

```bash
git init
git add .
git commit -m "CARE ENT Scope Camera v3.0.0"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/care-ent-scope-camera.git
git push -u origin main
```

Git will ask you to sign in to GitHub the first time.

> **Prefer no typing?** On your empty repository page click
> **uploading an existing file**, then drag the whole project folder into the
> browser window and press **Commit changes**. This works, but it can miss the
> `.github` folder because it starts with a dot — if the Actions tab stays
> empty, upload that folder separately.

### 4. Get your APK and EXE

Open the **Actions** tab on your repository. Both workflows run automatically on
every push, and you can also start them by hand with **Run workflow**. Download
the finished files from the **Artifacts** section of a completed run.

### Later changes

```bash
git add .
git commit -m "describe what changed"
git push
```

---

## Signing the Android release build

The debug APK is fine for installing on your own phones. If you want a signed
release build (for Play Store or wider distribution):

1. In Android Studio: **Build → Generate Signed App Bundle / APK**.
2. Create a keystore and **keep the file and passwords safe** — without them you
   can never update the app.
3. Choose **release**, then **Finish**.

---

## How the camera stream works

Useful if you ever need to debug the connection. Both apps implement this
identically.

**Commands sent to the camera** — 24 bytes, every 400 ms:

| Offset | Bytes | Meaning |
|---|---|---|
| 0 | 2 | Magic `0x9999`, little-endian |
| 2 | 1 | `1` = START, `2` = STOP |
| 3 | 21 | Zero |

**Video packets received from the camera:**

| Offset | Bytes | Meaning |
|---|---|---|
| 2 | 2 | Packet type, little-endian — `3` means video |
| 33 | 2 | Chunk index, little-endian, starting at 1 |
| 51 | … | JPEG payload |

Chunk *n* is written at `(n − 1) × 1388` bytes into the frame buffer. Chunk 1
starts with the JPEG marker `FF D8` and signals the start of a new frame; when
the next chunk 1 arrives, the previous frame is complete and is decoded.

---

## Differences between the two apps

| Feature | Windows | Android |
|---|---|---|
| Live view, zoom, pan, mirror, rotate | Yes | Yes |
| Brightness, contrast, saturation | Yes | Yes |
| Gamma, sharpness, denoise | Yes | Not included — these need per-pixel processing that would cost frame rate on a phone |
| Grid, crosshair, timestamp overlays | Yes | Yes |
| Freeze frame | Yes | Yes |
| Snapshots | JPEG to Documents | JPEG to the phone gallery |
| Recording | MP4, AVI fallback | H.264 MP4 |
| Video-only full screen | Separate window | Immersive, chrome hidden |

---

## Licence

Proprietary. See [LICENSE](LICENSE). This application is a camera viewing and
documentation aid and does not replace professional clinical judgement.
