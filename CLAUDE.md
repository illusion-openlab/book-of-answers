<!-- pico-cli:plugin-context:pico-spatial-agentic-tools:start -->
@./PICO-SPATIAL-AGENTIC-TOOLS.AGENTS.md
<!-- pico-cli:plugin-context:pico-spatial-agentic-tools:end -->

<!-- spatial-app-onboarding:start -->

# BookOfAnswers — Spatial App Onboarding Notes

## What this project is

A PICO Spatial SDK app scaffolded with `pico-cli project create --template volumetric`.
It opens a single **volumetric** `DefaultWindowContainer` (a 3D volume, not a flat panel)
and renders a SpatialUI scene inside it.

- Project name: `BookOfAnswers`
- Package: `com.illusion.bookofanswers`
- Template: `volumetric`, template version `6.0`
- Spatial SDK BOM: `6.0.0` (`gradle/libs.versions.toml` → `spatialBom`)
- compileSdk / targetSdk / minSdk: 35

## Why this structure

`volumetric` was chosen because the app is meant to present 3D content in a bounded
volume the user can place and resize in their space. The window-container form is
declared **in the manifest**, not in Kotlin — see the `pico.spatial.windowcontainer.*`
meta-data on `LaunchActivity`. Changing form/size/alignment is a manifest edit.

Container choice is architectural. Do not swap `DefaultWindowContainer` for `Stage`
or a plain panel just to make something compile.

## Key files

| File | Responsibility |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Declares the volumetric container: id, style=`2` (Volumetric), default size `960x960x960` dp, world scale, caption bar, base panel. This is where container behavior is tuned. |
| `app/src/main/java/.../Main.kt` | `mainApp(scope)` — app entry graph: `DefaultWindowContainer { PicoTheme { HomeVolume() } }`. |
| `app/src/main/java/.../content/HomeVolume.kt` | The actual volume content: loads `box.usdz`, sets up the 3D entity + SpatialUI text. Start feature work here. |
| `app/src/main/java/.../platform/LaunchActivity.kt` | Thin `SpatialLaunchActivity` subclass. The launcher entry point; holds the container meta-data. |
| `app/src/main/java/.../platform/SpatialApplication.kt` | Application class wiring `mainApp` into the Spatial runtime. |
| `app/src/main/assets/box.usdz` | Placeholder 3D model. Replace with real content; keep assets uncompressed. |
| `gradle/libs.versions.toml` | Version catalog, including `spatialBom`. |

## Spatial capabilities already in use

- Volumetric `WindowContainer` via manifest meta-data
- SpatialUI + `PicoTheme`
- 3D model loading from `assets/` (USDZ)
- `spatial-sense` and `spatial-tracking` are on the classpath but not yet used

## UI rule for this project (hard constraint)

All 2D UI is built with **SpatialUI** (`com.pico.spatial.ui.*`) wrapped in `PicoTheme`.
**Material / Material3 is forbidden** — no `androidx.compose.material`, no `material3`,
no `MaterialTheme`, no `Scaffold`. Colors and type go through `PicoTheme.colorScheme.*`
and `PicoTheme.typography.*`; do not hardcode `Color(0x...)` or `TextStyle(fontSize = ...)`.

Verified clean at scaffold time — keep it that way.

## Build, install, run

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PICO_HOME="$HOME/Library/pico/sdk"

./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app launch com.illusion.bookofanswers --activity .platform.LaunchActivity
```

Emulator with auto-reconnect (restarts if the process dies or adb drops):

```bash
pico-cli emulator start --watch --watch-interval 5 --wait-timeout 300 -y
```

Omitting `--avd` picks the newest installed bundle. The AVD this project runs on is
`Pico_Emulator_6_0` (bundle 6.0.0, spatial runtime `6.0.0.0-alpha.11`), auto-created
on first 6.0 start. The older `PICO_0.13` AVD is still installed as a fallback but
**cannot run this app** — see below.

## Environment gotchas on this machine

- `PICO_HOME` must be **lowercase** `~/Library/pico/sdk`. primer-cli compares the path
  case-sensitively; the uppercase `PICO` variant makes it report "PICO_HOME not configured"
  and hides Agent Vault / Templates. Fixed in `~/.zshrc`.
- `JAVA_HOME` is not set by the installer. Point it at the Android Studio JBR (above).
- **Emulator / SDK version must match.** The app targets Spatial SDK `6.0.0`. A `0.13.x`
  emulator ROM (spatial runtime `0.13.2.0-alpha.27`) refuses to run it:
  `com.bytedance.pico.matrix` force-stops the process and shows
  `EntitlementDialogWithGoUpdateRom`. This was hit and fixed by installing emulator
  bundle 6.0.0. If the app ever starts and dies instantly with no crash in logcat,
  check this first:
  ```bash
  pico-cli shell "dumpsys package com.pico.spatial.runtime | grep versionName"
  ```
  It must be on the same major line as `spatialBom`.
- Ordinary logcat is drowned by `PxrCompositor` / `MRService` spam — app lines rotate out
  of the buffer within a second. Always filter by tag, e.g.
  `pico-cli shell "logcat -d -v brief -s ActivityManager:I ActivityTaskManager:I AndroidRuntime:E"`.
- Disk: an emulator bundle is ~4 GB to download and ~11–13 GB installed. A *running*
  emulator additionally holds tens of GB of temp/snapshot space — stop it before judging
  free space.
- `pico-cli doctor` also reports `[error]` for the codex plugin host and codex
  `AGENTS.md` routing. Both are codex-specific and irrelevant when working in Claude Code,
  which routes through this `CLAUDE.md`.

## Natural next steps

1. Replace `box.usdz` with the real Book of Answers model and drive it from ECS.
2. Build the answer-reveal interaction (tap/grab on the volume) — needs both input and
   collision evidence on device, not just code review.
3. Add the answer text/data layer and render it with SpatialUI inside the volume.

Follow-up feature work should route through `spatial-app-dev-workflow`; 3D content
authoring through `spatial-editor`.

<!-- spatial-app-onboarding:end -->
