# SonderIcons

Every app on a HyperOS phone in your theme's icon style. No root.

HyperOS themes ship hand-drawn icons for a fixed list of apps and trace an outline of
everything else. SonderIcons draws the rest in the theme's own style, from each app's
monochrome glyph where it has one, and lets you pick an image or keep the theme's icon
for any app.

## How it works

- Reads the applied theme's icons through [Shizuku](https://shizuku.rikka.app/).
- Draws a glyph for every app the theme doesn't cover.
- Writes a new icons file into the Themes app's library and points the
  "Theme backup" theme at it. You apply it in Themes, as with any theme.
- "Restore original icons" points it back.

## Needs

- HyperOS with a "Theme backup" theme (made by Themes → Customize theme).
- Shizuku running.

## Licence

GPL-3.0-only.
