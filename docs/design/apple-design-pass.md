# Apple-design pass — Pox Kernel Manager

Applied on branch `design/apple-hig` using the [apple-design skill](https://github.com/dickwu/apple-design-skill)
(HIG-grounded review + design-improvement mode). The app is a native Android
app (Kotlin + Jetpack Compose, Material 3), so per the skill's own scope rule
this pass applies the **foundations** — accessibility, colour, typography,
layout, motion, craft — and keeps Android/Material conventions (bottom
navigation, switches, sliders, Material components). Apple-only conventions
(SF Symbols, Liquid Glass materials, edge-swipe back) were deliberately **not**
applied.

The subject, in one line: a monochrome instrument panel for a custom kernel —
/proc and /sys made legible. The pass strengthens that voice; it does not
re-theme the app.

## Audit findings (numbers, not adjectives)

Contrast ratios are WCAG 2.1 against the surface the text actually sits on.

| Where | Was | Ratio | Now | Ratio |
| --- | --- | --- | --- | --- |
| Hero card, provider version line (11sp) | `onPrimaryContainer` 60% on `#DCDCDC` | **4.06:1** | 75% | 6.5:1 |
| Hero card, device line (11sp) | 75% | 5.2:1 | 80% | 7.4:1 |
| Updater, package size label (11sp) | `outline #777777` on `#F5F5F5` | **4.29:1** | `onSurfaceVariant #474747` | 8.0:1 |

Other fixes:

- **Spacer bug**: `RootStatusBanner` used a horizontal `Spacer` between the
  title and body of a `Column` (no effect). Now 4dp vertical.
- **Icon squashed**: `UnsupportedReason` set `width(18.dp)` on an `Icon`
  (height stayed 24dp). Now `size(18.dp)`.
- **Spinner overflow**: the app-bar refresh indicator rendered a 40dp default
  spinner inside a 48dp icon target. Now 20dp.
- **Double announcement**: bottom-nav icons had `contentDescription` equal to
  the label below them; TalkBack read each tab twice. Icons are now decorative
  (`null`) and the label carries the text.
- **Whole-row targets**: switch cards only toggled from the 52dp Switch; the
  card is now `toggleable(role = Switch)` with the Switch purely visual.
  Same for settings radio rows and updater package rows
  (`selectable(role = RadioButton)`, `selectableGroup()` on the radio column).
- **Slider value for TalkBack**: sliders now carry `stateDescription` so the
  unit ("80%") is announced, not just "80".
- **Layout jump while loading**: "Re-apply" and "Export" swapped their label
  for a spinner, changing button width. The spinner now joins the label.
- **Font-scale resilience**: the dashboard status-badge row and the ZRAM chip
  row were fixed `Row`s that would clip at large font scale; both are
  `FlowRow`s that wrap.
- **"Off" vs "unsupported"**: disabled switch/slider cards dimmed only their
  Switch. Card content now dims together (60%), so a greyed-out row reads as
  unavailable even before the `UnsupportedReason` note below it.

## Token system

**Type** (`ui/theme/Type.kt`) — one ramp, 12 roles, all explicitly defined
(no more falling back to Material defaults for `titleSmall`, `labelMedium`,
`bodySmall`, …):

- Headlines 32/24/22sp SemiBold→Bold with negative tracking (−0.5 … −0.1).
- Titles 20/16/14sp; `titleMedium` is SemiBold by default so call sites don't
  stack `fontWeight` overrides.
- Body 16/14/12sp normal, tracking 0.1–0.2 (Material's 0.5 at 16sp is airy in
  Roboto).
- Labels 14/12/11sp medium.
- `includeFontPadding = false` on every style so text sits optically centred
  in the pills and cards it lives in.
- **Weight carries meaning**: SemiBold = titles, **Bold = live numbers,
  alerts, statements** (StatCard values, slider value pill, root banner,
  error notices). Bold overrides everywhere else were removed.

**Shape** (`ui/theme/Shapes.kt`) — one radius ladder, also fed into
`MaterialTheme.shapes` so dialog/chip defaults can't drift:

| Token | dp | Used by |
| --- | --- | --- |
| hero | 28 | the single expressive surface per screen (dashboard hero, updater status) |
| card | 20 | grouped cards |
| panel | 16 | inline notes (`UnsupportedReason`) |
| pill | 12 | badges, value chips, icon tiles |
| button | 14 | **all** buttons (was a mix of default-stadium, 12, 14, 16) |

**Spacing** — 4dp base grid: 16dp gutters, 8dp between stacked cards (4+4),
section headers 20dp top / 8dp bottom. Section headers were demoted
(`titleSmall` bold, accent colour) so they introduce cards instead of
competing with their 16sp titles.

**Signature** — tabular figures (`tnum`) on every live number (stat values,
download MB/s, slider pills): digits stop shifting as values tick. This is
the instrument-panel tell the design leans on, alongside the monochrome ramp.

**Motion** — unchanged by design: the tab `Crossfade` remains the only
orchestrated motion; Compose already respects the system animation scale for
reduced motion.

## Quality floor

- Responsive down to the smallest supported width (badge/chip rows wrap).
- Largest text scale survivable on every card (flexible rows, no fixed text
  heights); the dmesg terminal keeps a fixed 300dp viewport by intent.
- Dark theme unchanged: monochrome, AMOLED-leaning, error red only for
  meaning. Material You remains opt-in.
- No colour-only signalling anywhere (dot + label badges, border **width**
  change on the selected package, tappable rows with selection state).

## What was removed

One thing: the redundant `fontWeight = Bold` on the top-bar title and several
card titles (the ramp already carries the weight). Nothing structural — no
screen was re-ordered, no feature moved, no copy rewritten.
