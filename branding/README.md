# regexphone — Android icon pack

Concept A · `/phone/` mark on Material You violet (#5E5BFF) background.

## Install

Drop the contents of `res/` straight into your Android module's `src/main/res/` folder. The included `AndroidManifest.xml` reference is:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ...
```

## What's inside

| Path | Purpose |
| --- | --- |
| `res/mipmap-anydpi-v26/ic_launcher.xml`         | Adaptive icon — used on Android 8.0+ |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml`   | Adaptive icon (round launcher hint) |
| `res/drawable/ic_launcher_foreground.xml`        | Foreground vector layer |
| `res/drawable/ic_launcher_monochrome.xml`        | Monochrome vector — used by Android 13+ themed icons |
| `res/values/ic_launcher_background.xml`          | `ic_launcher_background` color (#5E5BFF) |
| `res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png`        | Legacy square fallback (48 / 72 / 96 / 144 / 192 px) |
| `res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_round.png`  | Legacy circular fallback (same sizes) |
| `playstore/ic_launcher-playstore.png`            | Google Play Store icon (512×512) |
| `source/*.svg`                                   | Vector sources — foreground only, monochrome only, full composite |

## Color tokens

| Role | Hex |
| --- | --- |
| Background (primary)      | `#5E5BFF` |
| Phone body                | `#FFFFFF` |
| Slashes                   | `#FFD8E4` |
| Phone screen / speaker    | `#FAF8FF` |

## Themed-icon behaviour (Android 13+)

The monochrome layer is filled with `#000000` and the system tints it according to the user's wallpaper-derived palette. The phone, slashes, screen, and speaker all collapse to a single tinted silhouette — content remains legible.

Generated from `brand.html` via the regexphone branding canvas.
