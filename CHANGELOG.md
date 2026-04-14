# Changelog

All notable changes to the **RevealSlider** library are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).  
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.0] — 2026-04-14

### Added

- **Dual-image mode** — `setBeforeBitmap(Bitmap)` and `setAfterBitmap(Bitmap)` let
  integrators supply two independent images for a true before/after comparison instead
  of a single image with a blur effect applied. `clearDualBitmaps()` returns the view
  to single-image mode. Both sides scale and render independently on the background
  thread with per-side cancellation tokens so a new "before" image never blocks an
  in-flight "after" computation.

- **`animateTo(targetPosition, durationMs)`** — smoothly animates the divider to any
  position using a `ValueAnimator` with `DecelerateInterpolator`. Fires
  `OnSliderChangeListener` on every frame. Cancels any in-progress animation before
  starting a new one.

- **`OnSliderErrorListener`** — `setOnSliderErrorListener(listener)` delivers
  background failures (including `OutOfMemoryError`) to the integrator on the main
  thread so fallback UI can be shown instead of a silent blank view.

- **Accessibility** — `RevealSliderView` is now focusable. `onInitializeAccessibilityNodeInfo`
  reports the view as `SeekBar` with a `RangeInfo` (0–100 %) so TalkBack reads the
  current position. `onKeyDown` handles DPAD_LEFT / DPAD_RIGHT for keyboard and
  D-pad navigation with a 5 % step per key press. Position changes announce via
  `announceForAccessibility`.

- **Unit tests** — `BlurEngineTest` covers `scaleBitmap` and `applyEffect` for
  edge cases: 1×1 bitmaps, square bitmaps, 8 MP sources (max-size guard path),
  all four `BlurType` values, extreme radii (1 and 25), minimum `pixelSize`, and
  visual-correctness checks for `DARK_FADE` and `FROSTED_GLASS` overlays.

- **CI test step** — GitHub Actions now runs `:revealslider:test` (Robolectric,
  API 28) before building the release AAR. The HTML test report is uploaded as a
  workflow artifact on every run, including failures.

### Changed

- **`HandlerThread` reattach fix** — `onAttachedToWindow` detects a dead
  `HandlerThread` (from a previous `onDetachedFromWindow` `quitSafely()` call) and
  creates a fresh thread + handler, then re-schedules the appropriate compute path.
  Fixes a frozen-slider regression when the view is reused in a `RecyclerView`,
  `ViewPager`, or fragment back-stack.

- **`OutOfMemoryError` now caught** — all three background `try/catch` blocks now
  catch `Throwable` instead of `Exception`. `OutOfMemoryError` extends `Error`, not
  `Exception`, so it was silently terminating the background thread without any
  notification to the integrator.

- **JMM data-race fix** — mutable configuration fields (`blurType`, `blurRadius`,
  `frostedAlpha`, `darkFadeAlpha`, `pixelSize`, `scaleType`) are now snapshotted as
  local `val`s before being captured by background-thread lambdas. Reads of shared
  state inside `bgHandler.post` were not guaranteed visible to the background thread
  by the Java Memory Model.

- **`@Volatile` on dual bitmaps** — `dualBeforeBitmap` and `dualAfterBitmap` are
  written on the main thread and guarded in background lambdas; `@Volatile` ensures
  the background thread sees the latest write without a synchronised block.

- **Max bitmap size guard in `BlurEngine.scaleBitmap`** — sources larger than
  4× the target area (or 4 MP minimum) are pre-downscaled before the final
  crop/fit step. A 12 MP photo that would otherwise OOM during scaling now uses
  ~2 MB instead of ~36 MB.

- **RenderScript removed (API 24–30 path)** — `android.renderscript.ScriptIntrinsicBlur`
  was deprecated in API 31. The API 24–30 blur path now uses a downscale → box-blur
  → upscale pipeline (`blurWithDownscale`) that is 3–5× faster on mid-range devices
  and contains no deprecated API calls. The `@Suppress("DEPRECATION")` annotation
  has been removed.

- **EXIF rotation** — `decodeSubsampled` in the demo app now passes decoded bitmaps
  through `applyExifRotation`, which reads `TAG_ORIENTATION` via `ExifInterface` and
  rotates portrait/landscape photos to their correct upright orientation before
  handing them to the slider.

- **Consumer ProGuard rules rewritten** — `consumer-rules.pro` now explicitly
  protects all three `@JvmOverloads` constructors (required for XML inflation),
  all three enums (`BlurType`, `SliderDirection`, `SliderScaleType`) with both
  `-keep enum` and `-keepclassmembers enum` covering `values()`, `valueOf()`, and
  `entries`, and the Compose wrapper class. Integrators with aggressive R8
  optimisation enabled will no longer see `ClassNotFoundException` or missing
  enum constants at runtime.

- **Explicit API warnings** — the library Gradle config now passes
  `-Xexplicit-api=warning` to the Kotlin compiler so any newly added public
  declaration without an explicit visibility modifier produces a compiler warning.

### Demo app

- Redesigned `MainActivity` layout using Material3 cards, a hero `RevealSliderView`
  with rounded corners and a primary-colour border, and a `NestedScrollView` control
  panel with labelled sections.
- **Mode toggle** — "Single Image" shows the blur-effect controls; "Two Images"
  shows before/after image picker tiles with sub-sampled decode on a background
  thread (`RGB_565`, `inSampleSize`) and EXIF-correct orientation.

---

## [1.0.0] — 2026-03-28

Initial public release.

- `RevealSliderView` custom View with draggable vertical divider
- Blur types: `GAUSSIAN`, `FROSTED_GLASS`, `DARK_FADE`, `PIXELATE`
- `SliderDirection`: `BEFORE_AFTER` / `AFTER_BEFORE`
- `SliderScaleType`: `CENTER_CROP` / `FIT_CENTER`
- XML attributes: `revealSrc`, `blurType`, `blurRadius`, `cornerRadius`,
  `showLabels`, `beforeLabel`, `afterLabel`, `sliderDirection`, `initialPosition`
- Background `HandlerThread` compute with generation counter to discard stale results
- API 31+ GPU blur via `HardwareRenderer` + `RenderEffect`
- API 24–30 blur via `RenderScript` (superseded in 1.1.0)
- Software fallback: 3-pass optimised box blur
- Jetpack Compose wrapper (`RevealSliderCompose`)
- JitPack publishing via `maven-publish`
