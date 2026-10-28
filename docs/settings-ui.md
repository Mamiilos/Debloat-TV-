# Android settings appearance and rendering

Settings use a static dark gradient, native icons, rounded cards and a contrasting focus fill. Main options are 80
layout pixels tall with 14-pixel gaps in the 1920×1080 coordinate system. Text, cards and accessibility bounds scale
with the TV's UI width. Home Settings and pickers show nine rows and scroll through the remaining options.

Recommendation status and attribution sit beside the main list. Home Screen contains the existing comparison and
notices below the Free My TV / Original choices. Mode switching, permission requirements and the watchdog are
unchanged. Playback history and Trakt use native Android widgets with the same dark palette, larger gaps and stable
button focus after a toggle.

## Rendering and memory

- The settings screen no longer creates an animated `Scene` or blurred focus masks. Focus keeps fixed bounds, so
  moving between options does not generate differently sized blur-cache entries.
- A frame callback is scheduled only while a spring or switch spinner is active. State files still refresh every
  two seconds (500 ms while working); an unchanged snapshot does not invalidate the view. Minute changes refresh
  notice timestamps.
- Wrapped explanatory layouts and paint objects are reused. The displayed package version is read once.
- History artwork jobs discard obsolete page generations before decoding or attaching images. Existing artwork,
  disk cache and local-history bounds remain in effect. No dependencies, image assets or network services were added.

## Physical-device check

On an Android 11 TV, before/after captures confirmed the layout and selected-row contrast. With Settings foreground
and no remote input, a 15-second sample after settling rendered 154 frames before and zero after. Process CPU-time
counters advanced 158 ticks before and two after; these are process counters, not a general startup benchmark.

The observed graphics cache was 63.05 MiB before and 7.03 MiB after. The earlier capture included 39.25 MiB of mask
filtered render targets; those entries were absent after the update. These samples share a process with Home and
depend on prior navigation and image caches, so they are not a fixed RAM saving or a controlled whole-app PSS claim.
An initial sample immediately after the update still contained startup work and was excluded from the idle result.
The signed APK remained approximately 1.5 MiB (4 KiB larger in this build).

For reproduction, open Settings, let startup and focus settle, reset `dumpsys gfxinfo io.freemytv:home`, and collect
another snapshot after 15 seconds without navigation. Use the process PID for `dumpsys meminfo`; querying only the
base package can report a different process. Repeat after navigating the menu to check that focus animations stop.
Do not interpret a zero-frame idle sample as a frame-rate measurement during navigation.
