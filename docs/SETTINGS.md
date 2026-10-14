# Settings: Automatic, Advanced, and per-screen overrides (proposal, 14 Oct 2026)

The owner asked to keep future customization in mind: **Automatic** and **Advanced** settings,
with per-monitor overrides where useful. This is the plan, so that each change on the way fits
it. Nothing here removes or moves an existing setting (VISION: preserve the established UI).

## Principles

- **Automatic is the default** and does the right thing without being asked: WindowsCraft
  measures (the Step 0 measuring is cheap enough to run lightly all the time for the few numbers
  it needs) and decides. It says what it decided, in plain words, right where the setting is —
  never a silent trade of sharpness or responsiveness (VISION 7).
- **Advanced** shows the knobs. Every Automatic decision has a matching Advanced setting; picking
  a value turns Automatic off for that one setting only ("Automatic" stays one click away).
- **Per-screen overrides** for what's naturally per screen (a movie wall vs. a desktop you work
  on). Each screen setting is "Like all screens" (the global value, Automatic or not) or its own
  value. Precedence: the screen's own value > the global Advanced value > Automatic.
- One place: the U menu. Global on ⚙ Settings, per screen on that screen's page (Picture tab).

## What there is, and what Automatic would do

| Setting | Where today | Automatic would… |
|---|---|---|
| Sharpness (720 / 1080 / full) | per screen | full, turned down while someone's connection can't keep up (already does) |
| Pictures a second (15 / 30 / 60) | per screen | 60 while it changes a lot (video, games), less for a still desktop (capture already sends only changes) |
| Shape on the wall (Auto / Fill / Fit) | per screen | (already Auto) |
| Sound, hear it from the wall | per screen | (unchanged: on) |
| Direct GPU sharing | global, Performance | on where it works (already: self-test + in-game check) |
| Off-screen pause | global, Performance | on |
| Distance frame-rate limit | global, Performance | on; per screen "never" for a screen you watch from afar |
| Shader pack on screens | global, Performance | off (true colours) |
| Sit-down view pixel for pixel | global, In-game monitors | on |
| Upload for screens (kbit/s) | stored, not shown | measured: what the connection to the server carries |
| Fewer mipmap updates | stored, not shown | on during video on big walls, off for text |
| **Lip sync vs. quickest picture** | new | in sync while you just watch a screen with sound playing (pictures wait for the sound); quickest while you (or a guest) use it |
| **Own wall's capture size** | new | the size the wall is on your screen (a 4K monitor across the room doesn't need 4K copies; full size when you sit down) |
| **Sound reserve** | new (dev switches today) | starts small, grows after a late arrival (the viewer's reserve already grows) |

Bold rows are where Step 0 found the trade-offs: lip sync is ~0.3 s while the picture takes the
quickest path; a 4K in-game monitor on the CPU route costs ~2.3 cores and halves the 1 % lows.

## Data

- Global: `config.ClientSettings` gets an `auto` flag per setting (absent = Automatic) next to its
  value; old settings files read as before (a value someone set stays theirs).
- Per screen: `Display` gets `overrides` (name → value; absent = like all screens), saved with
  the screen. Remote screens (someone else's) keep theirs in `RemoteOptionsScreen`'s store.
- One lookup, `Settings.of(screen, KEY)`, resolves the precedence, so a feature reads one place.

## UI

- ⚙ Settings → Performance: a small "Automatic" switch at the section's top. On: each row shows
  what Automatic picked and why (greyed control). Off: the rows work as today.
- A screen's Picture tab: rows that can be per screen get "Like all screens" as their first
  choice.
- No new screens or dialogs.

## Order of work

1. The model (`auto` flags, overrides, one lookup) — no visible change.
2. Lip sync vs. quickest (per screen, Automatic by default) — needs the owner's ears for the
   sync side; the measuring (A/V offset, refills) exists.
3. Own wall's capture size (Automatic) — measure on the RTX 4060 with a 4K in-game monitor.
4. The Performance "Automatic" switch and the per-screen "Like all screens" choices.
