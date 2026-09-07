---
name: oneui-design
description: Build Android UI that looks native on Samsung Galaxy devices. Use when designing or reviewing any screen that will run on One UI (Galaxy S/Z/Tab), or when the user asks for a "Samsung-style", "One UI", or "Galaxy-native" interface. Covers the four principles, the bottom-heavy layout law, exact dp/sp/color values, and the collapsing large-title header.
---

# One UI Design

Samsung's design system for Galaxy devices. Material 3 is *not* One UI — a stock
Material app looks visibly foreign on a Galaxy. The differences below are what
make it look native.

## The four principles

**Shape · Color · Structure · Interaction.** In practice they collapse into one
rule that drives every layout decision:

> **Split the screen. Content in the bottom half, context in the top half.**

Phones outgrew thumbs. One UI answers by pushing everything interactive into the
lower reach zone and letting the top half be a big, airy, non-interactive title
area. This is the single most recognizable One UI trait. If you copy one thing,
copy this.

Corollaries:
- Primary actions go at the **bottom**, never a top-right toolbar action.
- Dialog buttons are **full-width, stacked at the bottom**, not right-aligned.
- The top ~30% of a scrollable screen at rest is the collapsing title header.

## The collapsing large-title header

The signature component. Every One UI system app has it.

| State | Height | Title size | Alignment |
|---|---|---|---|
| Expanded (at rest) | ~200–230dp | 34–36sp, weight 600–700 | Left, 24dp margin, baseline near header bottom |
| Collapsed (scrolled) | 56–64dp | 18–20sp, weight 600 | **Centered** |

The title *moves* from bottom-left to top-center as you scroll. Interpolate on
scroll offset — don't cross-fade two labels.

## Layout values

- **Side margin / keyline: 24dp.** Not 16dp. This is the most common tell of a
  non-native app. Applies to cards, headers, list text, everything.
- Vertical gap between card groups: **16dp**. Inside a card, between rows: 20–24dp.
- Card internal padding: **24dp** horizontal, 18–20dp vertical.
- Touch targets: **48dp** minimum, with real space between them.
- Section header above a card group: 14sp, weight 600, **accent-colored**, 24dp
  left margin, 8dp below.

## Shape

- **Rounded rectangle containers ("focus blocks"): 26dp radius.** The big one.
  Group related settings into these instead of drawing full-bleed dividers.
- Nested / inner elements: 16dp.
- Buttons: fully rounded (pill) or 26dp.
- Dialogs and sheets: 26dp top corners.
- Never use sharp corners. Never use elevation shadows — One UI separates
  surfaces with **color**, not shadow. Cards are flat.

## Color

| Role | Light | Dark |
|---|---|---|
| Window background | `#F5F5F8` | `#000000` (true black, for AMOLED) |
| Card / focus block | `#FFFFFF` | `#1B1B1B` |
| Primary accent | `#0381FE` | `#3E91FF` |
| Text primary | `#101010` | `#FFFFFF` |
| Text secondary | `#8C8C8C` | `#9A9A9A` |
| Divider | `#E5E5E5` (inset 24dp) | `#2E2E2E` |

The window background is *darker* than the cards in light mode — the inverse of
Material's elevation model. Dark mode goes to true black, not `#121212`.

## Typography

Samsung ships **SamsungOne / SamsungSans**, which is not redistributable. Use the
system default (`Roboto` falls back correctly on Galaxy devices, where the system
font *is* SamsungOne — so specifying nothing is the most native choice).

Scale: 34sp display title · 20sp collapsed title · 17sp body · 15sp secondary ·
14sp section header · 13sp caption.

Text must survive **200% scaling** without clipping — use `sp`, never fix a
container's height around text.

## Interaction

- Motion is **quick and soft**: 250–350ms, decelerate-heavy easing. No bounce,
  no overshoot.
- Switches are the default toggle for a boolean setting — not checkboxes.
- Tapping anywhere on a settings row toggles its switch; the row is the target.
- Ripples are subtle and clipped to the 26dp card radius.

## Checklist

- [ ] 24dp side margins throughout
- [ ] Collapsing large title, centered when collapsed
- [ ] Settings grouped into 26dp flat cards, not divider lists
- [ ] Primary action in the bottom reach zone
- [ ] Window bg darker than cards (light) / true black (dark)
- [ ] No elevation shadows
- [ ] Text scales to 200% without clipping

## Sources

- [One UI Design Guide (PDF, official)](https://design.samsung.com/global/contents/one-ui/download/oneui_design_guide_eng.pdf)
- [One UI — Samsung Developer](https://developer.samsung.com/one-ui)
- [One UI accessibility: layout and typography](https://developer.samsung.com/one-ui/accessibility/layout-and-typo.html)
- [iF Design — One UI Design Principles](https://ifdesign.com/en/winner-ranking/project/samsung-one-ui-design-principles/755070)

Color and dp values are distilled from the official guide plus measurement of
One UI 6/7 system apps; Samsung does not publish a machine-readable token set.
