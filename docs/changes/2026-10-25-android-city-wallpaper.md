# Android city wallpaper

Build 10041 adds the approved city artwork to the Android application behind its
native controls. The library contains 302 views in 289 cities and 31 countries,
with 604 day/night WebP paintings (63,132,338 bytes). The importer verifies each
approved original before resizing to at most 960 by 1920 pixels. No asset server
or device location is needed.

Settings → App → Wallpaper → Living cities opens the city search, city-clock/day/
night light choices, art-weather choices, and motion switch. The owner edition
introduces the city background on its first upgrade, preserving a selected custom
photo. Existing built-in photos remain selectable. Customer copies offer the same
city library without enabling it automatically or including private photographs.

The bundled HTML/Canvas engine reuses the reviewed cloud, water, boat, lighting,
sky, city-life and weather layers. Android restricts requests to bundled files,
blocks navigation and network loads, and provides no JavaScript-to-native bridge.
Animation pauses when the activity is not resumed, respects disabled system
animations, limits canvas width and frame rates, and can be switched off while
retaining clock-based daylight. An unavailable system WebView falls back to a
static city painting. The weather is art direction, not live weather reporting.

Validation includes Android JVM tests, account/feedback visual regressions,
packaged resource/hash/dimension checks for every image, request-boundary checks,
and host clock/lifecycle tests. The local browser loaded all six rendering
canvases without console errors. Physical-device performance, battery use and
video playback have not been measured.

The release retains application IDs and the existing signing certificate, and
uses the existing owner and customer update feeds. Publication receipts and
artifact hashes are recorded separately from source/build validation.
