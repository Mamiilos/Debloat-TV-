# Android home performance

Recommendations, personalized picks, Continue Watching, episode artwork, progress and app navigation remain enabled.
The layout and focus transitions are retained. These changes optimize the native Android implementation without
adding dependencies, changing streaming apps or altering the TV's system settings.

## Changes

- Decode recommendation tabs when selected, retaining visited tabs within a 40 MiB pixel budget (16 MiB on Android
  low-RAM devices) and at most 12 entries. This avoids re-decoding the normal three-card tabs on every switch. The
  visible tab takes priority if it alone exceeds the budget. Unvisited tabs' originals remain in the existing bounded
  disk cache. A generation check skips queued work from an older tab or a paused home, and prevents stale results from
  repopulating the cache. Pause or memory pressure releases inactive tabs; visible cards remain available.
  When a different row needs decoding, retain the old row until the first three incoming cards are ready, then swap
  titles and images together. A three-second fallback keeps missing/broken artwork from blocking navigation indefinitely.
- Build the light design's temporary image at the wide card's width (1092 pixels at a 1080p UI), rather than preparing
  a full 1920×1080 image that is never displayed. Full-screen photo/scrim rendering keeps its original size.
- Decode Continue Watching artwork for the actual focused card: 420×236 in the mixed layout at 1080p. Calculate its
  color before moving the result to a hardware bitmap, avoiding a retained software copy plus a GPU texture.
  Keep at most 12 images/banners and bound failed-lookup metadata. Size changes invalidate older asynchronous requests.
- On pause or memory pressure, drop cached artwork outside the visible window and reusable shadow masks. Do not
  recycle bitmaps held by a view's display list. Cards just on screen remain ready for the return Home.
- Skip cards wholly outside the drawing clip. A settled drawer or app sheet no longer keeps a continuous vsync callback;
  focus, input, clock and data updates still wake the renderer.
- On the existing legacy-rendering path, draw card/app shadows only around the focused or lifted item, fading with
  focus. Unselected items retain their artwork, rounded corners and spacing. This avoids repeatedly compositing nine
  shadow slices per unfocused card during navigation; newer devices retain their existing shadow treatment.

## Research

The Reddit search found firsthand reports and adaptations, rather than a reproducible Free My TV performance benchmark:

- An [AndroidTVApps adaptation](https://www.reddit.com/r/AndroidTVApps/comments/1xf2m65/yet_another_google_tv_launcher_i_know_i_made_an/)
  emphasizes large backdrops and waiting for images before showing a new title. Its author also reports higher RAM use
  from full-screen photos. This motivated preserving artwork while limiting decoded images, not assuming a small APK
  guarantees low runtime memory.
- A [Fire Stick customization discussion](https://www.reddit.com/r/firestick/comments/1xfbjgl/launcher_modified_with_claude/)
  prioritizes smooth animations and mentions delays when returning Home. Those observations concern different hardware
  and software, so they are design feedback, not timing evidence for this TV.
- The [LGOLED discussion](https://www.reddit.com/r/LGOLED/comments/1xbhes8/has_anyone_tried_this_it_seems_to_add_a/)
  includes stability concerns across platforms. No numeric claim there is used as an Android baseline.

Implementation decisions follow Android's guidance on [appropriately sized bitmap decoding](https://developer.android.com/topic/performance/graphics/load-bitmap)
and [bounded memory/disk caches](https://developer.android.com/topic/performance/graphics/cache-bitmap). Bitmap bytes can
appear in native or graphics memory; neither Java heap alone nor APK size measures total image memory.

## Validation method

Run `tools/check.sh`. ImageSizing tests cover normal TV sizes, small sources, extreme panoramas/portraits, invalid bounds
and 500 generated sizes, checking power-of-two sampling and the decoded pixel budget.

For a physical comparison, wait for images to load, reset `dumpsys gfxinfo io.freemytv:home`, then navigate recommendations,
Continue Watching and apps twice without starting playback. Capture graphics statistics and `dumpsys meminfo` by the
home process PID afterward. Use the same titles, artwork and UI state when possible. A separate 15-second idle sample
checks background work. Human-paced passes vary in duration and number of key presses; report them as device observations,
not a controlled benchmark or a universal percentage improvement.

## Device observations

Initial comparisons on one Android 11 TV with a 1920×1080 UI:

| Observation | Before | Image/cache changes | With focus-only legacy shadows |
| --- | ---: | ---: | ---: |
| Home-process PSS after navigation | 131.8 MiB | 111.1 MiB | 69.4 MiB |
| Rendered frames in the manual pass | 1,660 | 1,516 | 988 |
| Reported janky frames | 199 (11.99%) | 526 (34.70%) | 178 (18.02%) |
| Frame deadline misses | 18 | 21 | 12 |
| CPU frame p50 / p95 | 10 / 20 ms | 9 / 21 ms | 8 / 20 ms |
| GPU frame p50 / p95 | 10 / 13 ms | 11 / 15 ms | 9 / 12 ms |

The first pass reduced observed memory but did not demonstrate smoother navigation; the tester also reported no
perceptible difference. After the focus-only shadow change, the tester reported smoother navigation, with lower median
CPU/GPU times and lower observed memory. The final screenshot confirms recommendation artwork, Continue Watching stills,
progress and the app shelf together. However, the final pass contained fewer frames and ended focused on Continue
Watching rather than the top tab; PSS includes shared process caches and depends on prior navigation. The firmware's
jank and deadline-miss counters are reported separately, and not all metrics improved against the original baseline.
Neither counter measures frames per second. No universal speedup or fixed RAM saving is inferred from these runs.

These navigation measurements used the initial one-spare recommendation cache. A subsequent tab-switch test exposed
visible image reloads on every revisit, so the final policy retains visited tabs within the pixel budget described
above. That deliberately trades some of the minimum memory figure for consistent tab navigation. The 69.4 MiB reading
is not a claim for the final warmed cache. In a settled 15-second sample before that cache adjustment, the home rendered
one new frame; CPU ticks were 138 versus 91 in the earlier baseline, so that sample does not establish lower idle CPU.

With the visited-tab cache, a subsequent sample was 100.6 MiB PSS after tab navigation. Logs contained six distinct
backdrop decodes and no repeated decode of those titles. The tester still reported a brief empty card during switches,
so the row handoff now waits for incoming artwork as described above. This figure was captured before that handoff
adjustment and is another observation, not a fixed footprint.

The tester confirmed that the final handoff removed the visible empty interval. The installed build's final screenshot
shows Explore artwork and Continue Watching stills intact; its home-process PSS was 80.5 MiB after returning to Explore.
Different retained tabs and focus states explain why that snapshot should not be substituted for a repeat of the initial
navigation benchmark. No fatal exception appeared in the final process log. Full `tools/check.sh` passed: 580 assertions,
384 translated keys across 26 catalogues, Android/LG/Samsung builds, ES5 and CSS checks. The Android APK remains about 1.5 MiB.
