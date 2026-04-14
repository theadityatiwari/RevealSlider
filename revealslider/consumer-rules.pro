# =============================================================================
# RevealSlider — consumer ProGuard / R8 rules
#
# These rules are packaged inside the AAR and applied automatically to every
# app that depends on this library. They protect the public API surface from
# being renamed or stripped by R8 / ProGuard during the app's release build.
#
# DO NOT add rules here that should only apply to the library's own build —
# those belong in proguard-rules.pro (library module). Rules here affect the
# integrator's app, so keep them as narrow as possible.
# =============================================================================


# ── Main view class ───────────────────────────────────────────────────────────
#
# Android inflates this class by its fully-qualified name when it appears in
# an XML layout:
#   <com.theadityatiwari.revealslider.RevealSliderView ... />
#
# If R8 renames the class, inflation throws a ClassNotFoundException at runtime.
# Constructors are listed explicitly because @JvmOverloads generates all three
# and XML inflation uses the (Context, AttributeSet) variant via reflection.
-keep public class com.theadityatiwari.revealslider.RevealSliderView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public *;
}


# ── Callback interface ────────────────────────────────────────────────────────
#
# Integrators implement OnSliderChangeListener in their own code and pass it
# via setOnSliderChangeListener(). R8 must not rename onSliderMoved() because
# the library calls it by the original name at runtime.
-keep public interface com.theadityatiwari.revealslider.RevealSliderView$OnSliderChangeListener {
    public *;
}


# ── Enums ─────────────────────────────────────────────────────────────────────
#
# -keep enum: prevents the class itself from being renamed (needed for
#   serialisation, Bundle round-trips, and readable crash reports).
#
# -keepclassmembers enum: ensures values(), valueOf(), and all constants survive
#   even when R8's "aggressive dead code removal" is enabled. Also covers
#   Kotlin's `entries` property which compiles to a static getEntries() method.
#
# Without these, code like:
#   BlurType.valueOf("GAUSSIAN")         // breaks if values stripped
#   BlurType.entries                     // breaks if getEntries() stripped
#   intent.putExtra("type", BlurType.GAUSSIAN.name)  // breaks if renamed
# ... all fail silently or crash at runtime.

-keep enum com.theadityatiwari.revealslider.BlurType
-keepclassmembers enum com.theadityatiwari.revealslider.BlurType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public **;
}

-keep enum com.theadityatiwari.revealslider.SliderDirection
-keepclassmembers enum com.theadityatiwari.revealslider.SliderDirection {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public **;
}

-keep enum com.theadityatiwari.revealslider.SliderScaleType
-keepclassmembers enum com.theadityatiwari.revealslider.SliderScaleType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public **;
}


# ── Jetpack Compose wrapper ───────────────────────────────────────────────────
#
# RevealSliderCompose.kt compiles to a static class RevealSliderComposeKt.
# Compose's own consumer rules protect @Composable internals, but the
# generated class name must be preserved so call sites are not broken.
-keep public class com.theadityatiwari.revealslider.RevealSliderComposeKt { public *; }
