# Display map + magic onboarding — design

Date: 2026-10-24. Approved by Carter in chat ("Ok to build both"). Builds on feat/v1 at v0.3.1.

## Why

Carter, after play-testing the monitors: picking "Display 1 2 3 4 / Mirror / Extend" gives no idea which screen is which.
He wants what his HP EliteBook does on Windows when he plugs in monitors: a picture of every screen where it really is,
numbered, an Identify button, and dragging screens to rearrange them.

Separately: he "still has 0 idea how to use the grimoire". The handbook he spawned with is the old one (it only updates
with `/handbook`), and nothing in game explains the Grimoire or the Rebinding Seal.

## Part A — Display map (hologram settings screen)

### What Carter decided
- **Drag to rearrange**, like Windows Display Settings, with Identify and Apply.
- **Mirror can pick which real monitor** it copies (today Mirror always copies the main screen).
- **Identify shows numbers on the real screens AND on the holograms** in the world.
- **Real monitors are letters (A = main, then B, C… ), Office displays stay 1–4.** No existing hologram changes meaning.

### Behaviour
- In `HologramSettingsScreen`, the "Display 1 2 3 4" button row becomes a **map**: one rectangle per screen, scaled and
  placed by the real global display coordinates, sizes proportional.
  - Real monitors: grey, big letter, small name ("MacBook", "LG"). A is the main display; B, C… ordered left-to-right
    then top-to-bottom by origin (stable while nothing is plugged in or out).
  - Office displays (Extend virtual displays 1–4): element of the existing Office style, big number. Only displays
    that currently exist are drawn; Extend picking a not-yet-existing number still works (show it as a dashed slot at
    the end of the row, as today's button would create it).
  - The display the hologram shows is outlined white (the existing "selected" style).
- **Extend mode:** click an Office display to choose it. **Drag an Office display** to move it; on release it snaps
  edge-to-edge against the nearest screen (left/right/above/below, edges aligned or overlapping like Windows, never
  overlapping area, never floating detached). **Apply** sends the new origin to the helper, which moves the virtual
  display for real (`CGConfigureDisplayOrigin`, same mechanism as `VirtualDisplays.arrange`), so the mouse crosses at
  the new edge. **Real monitors cannot be dragged.** Revert/Cancel = close without Apply.
- **Mirror mode:** click a real monitor (A/B/C…) to choose which one this Mirror copies. Stored in the hologram spec;
  old Mirrors default to the main display. If the chosen monitor is unplugged, fall back to main and say so in the map.
- **Identify** (button on the map): for 3 s,
  - a big letter/number appears on every real monitor and every Office display (helper draws a borderless, click-through
    overlay window per display, like Windows), and
  - every hologram in the world shows its number/letter big in the middle — on the owner's client for all their
    holograms; other players see the number on Public/shown-to-them holograms but **never monitor names**.
- **Persistence:** the arrangement the user Applies is remembered by the helper (per display slot, relative to the main
  display) and re-applied when the virtual displays are created again (restart, re-login). Falls back to the current
  automatic placement if the saved spot no longer touches any screen.
- **Windows players:** same map with their real monitors, Mirror-pick and Identify where the Windows platform can
  enumerate and capture monitors; Extend stays greyed as it is today. If a platform can't enumerate monitors, the map
  shows just the main screen and Identify only numbers holograms.
- **Fits without scrolling** at any GUI scale (screen polish rule); the map takes the space of the old button row plus
  whatever the two-column layout allows, scaling to fit.

### Constraints
- No new permissions on the Mac beyond what the helper already has (Screen Recording, Accessibility). No TCC edits.
- Protocol additions are versioned like existing helper messages (helper and mod may be one release apart: an old
  helper without the new messages → map shows positions from what the mod already knows, Apply/Identify greyed with a
  "update the helper" hint).
- Monitor names never leave the owner's computer (not sent to the server).
- Mirror capture of a non-main monitor still excludes Minecraft's own window (same as today's Mirror).

## Part B — Magic onboarding

1. **Handbook updates itself:** on join, outdated copies of the Office Handbook in the player's inventory (and ender
   chest is out of scope) are replaced in place with the current one (the `/handbook` UPDATED path). No chat spam unless
   something was replaced ("Your Office Handbook was updated.").
2. **Tooltips:**
   - Grimoire: unbound → "Right-click to open and choose your element." Bound → element + level, then
     "Off hand + empty main hand: tap right-click to cast, hold to charge. Shift+scroll or hold R: pick spell."
   - Rebinding Seal: "Use with your Grimoire open to choose your element again. Consumed."  (match the real mechanic —
     builder reads how the Seal is actually used and words the tooltip to it.)
3. **Holding-it-wrong hint** (action bar, at most once per 30 s, client side): Grimoire in main hand and right-click
   pressed while bound → "Put the Grimoire in your off hand (F) with an empty main hand to cast." Grimoire in off hand
   with an item in main hand and right-click → nothing (that click belongs to the item), no hint.
4. **"How to cast" page** in the Grimoire screen (a tab/page; no scrolling), same text as the handbook's Casting page
   plus the hand-sign note.
5. Handbook text stays the source; tooltip and page strings live in en_us.json.

## Non-goals
- Rearranging real monitors. Changing the macOS main display. Resolution/scaling settings.
- Rewriting the hologram settings screen beyond the display section.
- New magic mechanics.

## Left to the builder
- Exact map drawing style, snap tolerance, Identify overlay look (big white numeral on dark rounded square, like Windows).
- Message names/ids for the new helper messages (DISPLAYS list with origin/size/name/isMain/slot; SET_ORIGIN; IDENTIFY;
  MIRROR_DISPLAY), and how the spec field for Mirror's monitor is stored (stable display identity: CGDisplay UUID on Mac).
