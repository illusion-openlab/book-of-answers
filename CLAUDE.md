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
- Package: `tech.illusion.bookofanswers`
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
| `app/src/main/assets/book.usdz` | The book model — `Simple_animated_book.usdz`, ~4 MB, one skinned mesh, 16 joints, timeline frames 5–400 @ 120 fps (`getDuration()` = 3.2917 s). USD units are cm and it is **already real-world scale: `BOOK_SCALE = 1`, do not rescale.** Shipped byte-identical to the download. |
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
  book down. Do not iterate on `pitch` to aim the cover. `Rz(+90) · (1,0,0) = (0,1,0)`, which is
  why `ROLL_CLOSED = +90` (not −90) puts the *front* cover up in the resting pose.
- **This model's animation carries its own 90° of pose change, so entity `roll` must be
  interpolated — a constant cannot work.** Measured: at entity `roll = 0` the closed book stands
  upright (0.0279 × 0.2052 in x/y) while the open book is dead flat (0.4409 × 0.0291). So a
  constant `roll = 0` gives a good open pose and a book standing on end when shut, and a constant
  `roll = ±90` gives a good closed pose and an open book that is a vertical sliver — both were
  seen on screen. `poseFor` therefore lerps `ROLL_CLOSED = 90` → `ROLL_OPEN = 0` against openness.
- **Because the entity rotates, the position compensation must rotate the centre offset too.**
  `position = BOOK_CENTER − R · (scaled centre offset)` with `R = Ry(yaw) · Rz(roll)` — the matrix
  multiplied out in `poseFor`'s KDoc. Subtracting the raw offset (correct for a non-rotating
  entity) puts the book in the wrong place here.
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
- **`findSkinnedMeshEntity()` returns a list — animate every entry.** The current model has one
  skinned mesh, but the Mythical-Newar model had three (top cover, bottom cover, pages), each with
  its own `Take_001` controller.
  `BookScene` once took `.firstOrNull()`, so exactly one slab peeled off while the rest stayed a
  closed block; together with the centre compensation below it read as **"the book didn't open, it
  just slid right."** The previous model had a single skinned mesh, which is why the bug hid for so
  long. `BookAnimator` therefore takes `List<AnimationPlaybackController>`, applies
  `setTime`/`resume`/`pause` to all of them, and reads progress from only the first (`clock`) so
  multiple samplers can't fight. Startup logs `skinnedMeshes=3, controllers=3` — if those two
  numbers ever disagree, a mesh lost its clip.
- **Pin the end pose with `setTime`; never let the poll's landing point decide it.**
  `AnimationPlaybackController.getTime()` on this emulator sat at exactly `0.0` for ~645 ms after
  `resume()` and then jumped straight to ~1.0 s, so the last poll overshoots badly: the close
  segment measured `tEnd = 4.540` against a `duration` of `4.4583` — past the end of the clip, and
  the mesh stopped in a pose outside it (a book frozen half-open). `playSegment` now ends with
  `setTime(target); pause()` on every controller, `target` being the duration-clamped endpoint.
  The open segment overshot too (`1.000` vs a target of `0.875`) but that landed inside the
  161–490 open hold, which is why only closing looked broken.
- **The emulator's screenshot is not a faithful witness for the 2D panel.** A capture showed the
  book correctly open while the panel still displayed the prompt — yet the logs for that same frame
  read `drawAnswer -> 不必耿耿于怀` followed by
  `panel compose content=AnswerText(text=不必耿耿于怀) alpha=1.0`. The 3D content in the capture was
  current and the panel's texture was stale. Trust logs over screenshots for panel copy, and never
  conclude "the state machine didn't fire" from a screenshot alone.
- **Gate evidence on log lines, not on `sleep`.** Several wrong conclusions here came from
  screenshots that landed before the tap they were meant to observe, or after the next one. Each
  `pico-cli shell` round trip costs seconds, so even log polling lags — the reliable trick is to
  make the state under test **last much longer than the sampling jitter** (the throwaway probe held
  the open pose 45 s) rather than trying to hit a narrow window.
- **A downloaded USDZ can bind `skel:animationSource` to the wrong prims — but that never broke
  this app, and the current asset ships unpatched.** Both Sketchfab models carried the defect
  (`GetAnimQuery()` invalid on the shipped file), and both animated fine in the app, so the SDK
  plainly does not resolve animations through UsdSkel's inherited binding. Patch a **temp copy**
  when you need offline skinning measurements; leave the shipped asset alone. Correcting an earlier note here: the defect below is real and was fixed, yet it
  was never the cause of the symptom. The SDK reported `animated=true` *while the binding was still
  broken*, which proves it does not resolve animations through UsdSkel's inherited
  `skel:animationSource` at all. The repair only makes the file conformant for pxr/`usdrecord` and
  other spec-following tools; the app-visible bug was the `firstOrNull()` above.
  This model shipped from Sketchfab with `skel:animationSource` authored on the three *Mesh*
  prims — which are **siblings** of the `Skeleton`, not its ancestors. UsdSkel resolves a
  skeleton's animation source from itself or an ancestor only, so the skeleton got nothing:
  joint rotations existed in the file (51 samples, 65→600) but the skinning transforms stayed
  identity at every frame, and the mesh never deformed. The SDK still enumerated the animation
  resource, so `animated=true` and playback timed out on nothing. Fixed by applying
  `SkelBindingAPI` to the `Skeleton` and pointing `skel:animationSource` at `Take_001`, then
  repacking. **`app/src/main/assets/book.usdz` is therefore a patched file; the untouched
  download is `~/Downloads/Kiano88_-_Book_of_Mythical_Newar_3D.usdz`, and git history before
  the fix commit holds the broken copy.** Diagnose any future "animation does nothing" model in
  two lines of pxr — no device needed:
  ```python
  skelq = UsdSkel.Cache().GetSkelQuery(binding.GetSkeleton())
  print(bool(skelq.GetAnimQuery()))   # False ⇒ the animation is not bound to the skeleton
  ```
  Repack with `UsdUtils.CreateNewUsdzPackage` — plain `usdzip <out> scene.usdc` silently drops
  the texture, and a hand-built `zip` breaks usdz's alignment/no-compression requirement.
  Two independent confirmations that a repair took: `GetAnimQuery()` turns valid, and
  `usdrecord` at two frames stops producing byte-identical images.
- **Measure deformed poses offline, never from `getVisualBounds()`.** That runtime call reflects
  the entity transform but *not* skeletal deformation, so it cannot tell you where the open book
  sits. `BookScene`'s `CENTER_CLOSED` / `CENTER_OPEN` and the collider union all come from pxr
  skinning computed per frame. Measured for the current model (metersPerUnit = 0.01):
  frames 5–100 closed hold → size 0.0279 × 0.2052 × 0.2893, centre (0, 0.1011, 0);
  100 → 190 opens; frames 190–302 open hold → size 0.4409 × 0.0291 × 0.2893,
  centre (0, 0.0131, 0); 302 → 400 closes, and frame 400 is identical to frame 5.
  Only y moves (the centre drops 0.088 m). **`BookAnimator` starts from frame 100, not 5** —
  5–100 is a dead 0.79 s hold, and playing it would stall the tap response for almost a second.
  `ComputeSkinnedPoints` returns `True` even when the skinning transforms are all identity —
  check the transforms, not the return value.
- **A collider much larger than its object can be worse than a tight one.** The book's collider
  must cover both the closed and open poses, because `CollisionComponent` follows the entity
  transform but *not* skeletal deformation — so it is the union of the two measured bounding
  boxes, not a guess. Resist padding it into a big cube: an earlier 0.50 m cube reached 25 cm
  past the book on all sides, and a hand reaching in would already be inside it before touching
  anything, which plausibly suppressed the poke rising edge. Unproven, but a tight box costs
  nothing.
  **Open item with the current model:** because its entity rolls +90 → 0, the collider rolls with
  it, so the local-space union box (0.46 × 0.22 × 0.31 at y = 0.1011) stands 0.46 m tall in world
  space at the closed pose — roughly 0.23 m of empty box above and below a flat shut book. Fine
  for ray and pinch, over-eager for a fingertip. A static collider cannot track a rotating entity,
  and moving it to a bare mesh-less child entity was already tried and was not hittable. This is
  the first thing to revisit if fingertip poke feels too sensitive on a device.

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
pico-cli app launch tech.illusion.bookofanswers --activity .platform.LaunchActivity
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
pico-cli app launch tech.illusion.bookofanswers --activity .platform.LaunchActivity --device emulator-5554
pico-cli shell "logcat -d -v brief -s AnswerSource:V BookScene:V BookAnimator:V HomeVolume:V" --device emulator-5554
```

Healthy startup is exactly two lines: `AnswerSource: loaded 1094 answers` and
`BookScene: book loaded, animated=true, tapBox=..., center=...`. `animated=false` means the
animation fell back to still mode. Any `BookAnimator` line at all is a problem — the tag only
ever logs segment timeouts, so **silence is the pass condition**.

**`animated=true` does not prove the book will actually move**, and neither does the absence of a
`BookAnimator` timeout. Both were true throughout the "book only slides right" bug. The startup
line now carries `skinnedMeshes=N, controllers=N` precisely because that pair is the signal worth
reading: every skinned mesh must have a controller, or part of the book stays shut. A sideways
slide with no opening is the signature of `BookScene`'s centre compensation running for a
deformation that never arrived.

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
