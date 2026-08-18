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
| `app/src/main/java/.../Main.kt` | `mainApp(scope)` — app entry graph: `DefaultWindowContainer { PicoTheme { HomeVolume() } }`. **`PicoTheme` lives here** — do not add a second wrapper downstream. |
| `app/src/main/java/.../content/HomeVolume.kt` | The one screen. Wires everything: `AnswerSource` → `BookState` → `BookScene` → `AnswerPanel`, and owns `detectSpatialTapGesture`. `PANEL_OFFSET` (panel placement) is here. Start feature work here. |
| `app/src/main/java/.../content/BookScene.kt` | `loadBookScene()` — loads `book.usdz`, applies `BOOK_POSITION` / `BOOK_ORIENTATION`, attaches the box collider + `InteractableComponent`, and builds `BookAnimator` when the model has animation. Both transform constants are screenshot-calibrated; read their KDoc before touching them. **`BookScene.close()` owns the entity's destruction** (`Entity.destroy`) as well as the animator's — nothing else in the app destroys it, and on the late-load path the entity never enters `content`, so no container teardown would reclaim it. |
| `app/src/main/java/.../content/BookAnimator.kt` | Wraps the model's built-in open animation: `showClosed()` / `open()` / `closeThenOpen()`, plus segment timeout guarding. Owns the `AnimationPlaybackController`. |
| `app/src/main/java/.../content/BookState.kt` | The interaction state machine (`BookPhase`: Closed → Opening → Revealed → Reshuffling). Pure Kotlin, unit-tested; holds the "swap the answer while the book is shut" rule. |
| `app/src/main/java/.../content/AnswerPanel.kt` | The SpatialUI answer panel (`PanelContent.Prompt` / `AnswerText`), fixed-size with `backgroundMaterial`. Copy wording is fixed by design doc 4.4.1 — 「触碰」, not 「点击」/「揭晓」. |
| `app/src/main/java/.../data/AnswerSource.kt` | Reads `assets/answers.txt` and builds the repository. **Must stay non-suspend and be called inside `remember { }`** — its broad `catch (Throwable)` would swallow `CancellationException` in a coroutine. |
| `app/src/main/java/.../data/AnswerParser.kt` | Parses the corpus file into `Answer`s — one entry per line, blank lines dropped, each line trimmed. **That is all it does:** there is no dedupe (that happened once at corpus-build time) and no comment syntax, so a `#` line would ship as an answer. Unit-tested. |
| `app/src/main/java/.../data/AnswerRepository.kt` | Random draw without immediate repeats. Unit-tested. |
| `app/src/main/java/.../data/Answer.kt` | The answer value type. |
| `app/src/main/java/.../platform/LaunchActivity.kt` | Thin `SpatialLaunchActivity` subclass. The launcher entry point; holds the container meta-data. |
| `app/src/main/java/.../platform/SpatialApplication.kt` | Application class wiring `mainApp` into the Spatial runtime. |
| `app/src/main/assets/book.usdz` | The book model (~4 MB, bounding box 0.03 × 0.21 × 0.29 m — already real-world scale, do not rescale). Carries the open/close animation the app plays. |
| `app/src/main/assets/answers.txt` | The answer corpus: 1094 entries, longest is 19 characters. `AnswerSource` logs `loaded 1094 answers` — a count of 3 means the asset did not ship. |
| `app/src/test/java/...` | Unit tests for `AnswerParser`, `AnswerRepository`, `BookState`. Run with `./gradlew testDebugUnitTest`. |
| `gradle/libs.versions.toml` | Version catalog, including `spatialBom`. |

## What the app does

A closed book sits in the volume with a prompt panel floating just above it. Touching the
book opens it and a random answer from the corpus replaces the prompt. Touching again shuts
the book, swaps the answer **while it is shut**, then reopens it.

## Spatial capabilities already in use

- Volumetric `WindowContainer` via manifest meta-data
- SpatialUI + `PicoTheme`
- 3D model loading from `assets/` (USDZ), driven by the model's own built-in animation
- `CollisionComponent` + `InteractableComponent` and `detectSpatialTapGesture` for touch
- `spatial-sense` and `spatial-tracking` are on the classpath but not yet used

## Interaction / placement gotchas learned the hard way

- **`EulerAngles(pitch, yaw, roll)` is extrinsic ZXY** (`M = M_yaw_Y · M_pitch_X · M_roll_Z`).
  The book model's cover normal is local `+X`, which is the pitch axis — so at `roll = 0`,
  changing `pitch` cannot change which way the cover faces at all. `roll` is what lays the
  book down. Do not iterate on `pitch` to aim the cover.
- **The model's open animation itself adds roll ≈ +90°.** At `roll = 0` the closed book
  stands upright and the open book is dead flat. So closed-looks-good and open-looks-good
  pull `roll` in opposite directions; the current value is a deliberate compromise, see the
  `BOOK_ORIENTATION` KDoc.
- **`ShapeResource.createBox` centres on the entity origin, and this model's origin is at the
  book's base**, not its centre (`getVisualBounds().center.y ≈ 0.101`, half the height). The
  `offsetByTranslation(bounds.center)` in `BookScene.kt` is therefore load-bearing. A wrong
  offset fails **silently**: the resource is still valid, the hitbox is just in the wrong
  place. Nothing throws and nothing logs.
- **The emulator composites the volumetric window later than the app finishes loading.** Measured:
  the window is still absent from screenshots at t+2.2–4.0s after launch, while
  `AnswerSource: loaded …` → `BookScene: book loaded` spans only ~1–2s. So startup-ordering work
  (e.g. attaching the prompt panel before the model load) is **not observable here** — the
  intermediate state is over before anything is on screen. Judge it on a device.
  `pico-cli capture screenshot` itself costs ~2s and degrades to 10s+ under repeated use, so it
  cannot sample anything shorter than a couple of seconds.
- **The emulator cannot exercise spatial input.** `adb shell input tap` injects 2D screen
  coordinates and does not reach a volumetric window's hit-testing — verified: tapping the
  book's exact on-screen centroid produces no `HomeVolume` log line at all. Ray and poke
  interaction must be validated by hand on a device or in the emulator UI.
- `pointerInput`'s key must be `scene`, not `Unit` — the book loads asynchronously, and with
  `Unit` the `TargetEntity` closure stays pinned to the first-composition `null`. **Observed
  on device, not documented:** a `null` target appeared to make *everywhere* hittable.
  `detectSpatialTapGesture` is absent from the Agent Vault api-reference, so its `null`-target
  semantics have no citable source. The fix holds either way — keying on `scene` means the
  closure always reads the current entity.
- **Two `detectSpatial*` calls must never share one `pointerInput` block.** They compete for the
  same event stream. `HomeVolume` therefore chains two separate `pointerInput(scene)` modifiers:
  one for `detectSpatialTapGesture` (pinch / ray / gaze / controller), one for
  `detectSpatialPointerEvent` (fingertip poke).
- **Fingertip poke is `InteractionKind.Poke` on the generic pointer event, not hand tracking.**
  The SDK's own design doc defines Poke as "tap interactive objects using the tip of the index
  finger" and names event source type as the way to filter it. Raw hand joints
  (`HandTrackingProvider`) would need **Full Space**, which a volumetric `WindowContainer`
  cannot be — so joints are the wrong tool here, not merely the heavier one. Note the doc's
  caveat about needing hand tracking for "strict physical contact" applies to **Pinch**, whose
  activation range deliberately ignores collider size; Poke does not carry that caveat.
- Fire poke on `SpatialPointerInfo.isDownEvent()`, not `pressed`. `pressed` is true every frame
  of the contact and turns one touch into a stream; `isDownEvent()` is
  `changedToDownIgnoreConsumed()`, the per-pointer rising edge. Each hand is its own
  `pointerId`, so two-handed support needs no extra code. Return `false` from the callback so
  the tap detector still receives its copy.
- `detectSpatialPointerEvent` and `SpatialPointerInfo` are **also** missing from the
  api-reference. Real signature, from `javap` on
  `com.pico.spatial.ui/foundation/6.0.0/…/foundation-6.0.0.aar`:
  `PointerInputScope.detectSpatialPointerEvent(context, target: TargetEntity?, (List<SpatialPointerInfo>) -> Boolean)`
  — same shape as the tap detector, target included, so the SDK filters by entity for you.
- **A collider much larger than its object can be worse than a tight one.** The book's collider
  must cover both the closed and open poses, because `CollisionComponent` follows the entity
  transform but *not* skeletal deformation — so it is the union of the two measured bounding
  boxes, not a guess. Resist padding it into a big cube: the earlier 0.50 m cube reached 25 cm
  past the book on all sides, and a hand reaching in would already be inside it before touching
  anything, which plausibly suppressed the poke rising edge. Unproven, but the tight box costs
  nothing.

## UI rule for this project (hard constraint)

All 2D UI is built with **SpatialUI** (`com.pico.spatial.ui.*`) wrapped in `PicoTheme`.
**Material / Material3 is forbidden** — no `androidx.compose.material`, no `material3`,
no `MaterialTheme`, no `Scaffold`. Colors and type go through `PicoTheme.colorScheme.*`
and `PicoTheme.typography.*`; do not hardcode `Color(0x...)` or `TextStyle(fontSize = ...)`.

Verified clean at scaffold time and re-verified after the book interaction landed — keep it
that way. `AnswerPanel.kt` is the reference for how to do it: `PicoTheme.colorScheme.*` /
`PicoTheme.typography.*` roles, `com.pico.spatial.ui.design.Text`, and
`com.pico.spatial.ui.foundation.material.backgroundMaterial`.

One caveat on checking SpatialUI symbols: the Agent Vault api-reference under
`$PICO_HOME/6.0/agent-vault/spatial/api-reference/` is **package-scoped, not exhaustive**.
`backgroundMaterial` is real but appears in none of those files. Absence there is not proof a
symbol does not exist; a successful `compileDebugKotlin` is the stronger signal.

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
- **The window's pose varies between launches on the emulator.** `pico.spatial.windowcontainer.id`
  is now `BookOfAnswersVolume` (it was the scaffold's `YourVolumetricWindowContainer`). Renaming it
  plausibly drops whatever placement the system had saved under the old id, but that was **not**
  confirmed: across five launches the window came up at two different poses both before and after
  the rename, and the last post-rename launch matched the pre-rename pose exactly. Treat window
  pose as non-deterministic here and do not read placement regressions into it — compare the
  *relative* layout (prompt panel directly above the book, both centred) instead, which held in
  every screenshot.
- `pico-cli doctor` also reports `[error]` for the codex plugin host and codex
  `AGENTS.md` routing. Both are codex-specific and irrelevant when working in Claude Code,
  which routes through this `CLAUDE.md`.

## Verify before claiming anything works

```bash
./gradlew assembleDebug && ./gradlew testDebugUnitTest
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli shell "logcat -c" --device emulator-5554          # ALWAYS clear first
pico-cli app launch com.illusion.bookofanswers --activity .platform.LaunchActivity --device emulator-5554
pico-cli shell "logcat -d -v brief -s AnswerSource:V BookScene:V BookAnimator:V HomeVolume:V" --device emulator-5554
```

Healthy startup is exactly two lines: `AnswerSource: loaded 1094 answers` and
`BookScene: book loaded, bounds=..., center=..., animated=true`. `animated=false` means the
animation fell back to still mode. Any `BookAnimator` line at all is a problem — the tag only
ever logs segment timeouts, so **silence is the pass condition**.

Clear logcat before *every* launch. A stale line from the previous run reads as current and
has already produced one wrong conclusion in this project.

Note there are two devices attached on this machine (a physical PICO and the emulator), so
`--device emulator-5554` is not optional.

## Natural next steps

1. **On-device acceptance (Task 10).** Real finger `InteractionKind.Poke`, ray-tap hit
   accuracy against the collider, animation speed, and how the placement reads at a
   user-chosen window height — none of these can be settled in the emulator.
2. Revisit `BOOK_ORIENTATION`'s closed-vs-open roll compromise once there is a real device
   viewpoint to judge it from.
3. Consider letting `AnswerPanel` wrap/grow instead of its fixed `Modifier.size(...)`. The
   longest corpus answer (19 chars) currently fits on one line with room to spare, so this is
   headroom for a future corpus, not a present bug.

Follow-up feature work should route through `spatial-app-dev-workflow`; 3D content
authoring through `spatial-editor`.

<!-- spatial-app-onboarding:end -->
