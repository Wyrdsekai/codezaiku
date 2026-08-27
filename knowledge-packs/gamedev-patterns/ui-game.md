# Game UI Patterns

## When to use
- Building in-game HUD, menus, inventory screens, or dialog systems
- Choosing between immediate-mode and retained-mode UI for a game
- Integrating UI with game state (health bars, minimaps, quest trackers)
- Supporting localization across multiple languages in UI text

## Pattern

### Immediate Mode UI
- UI drawn every frame by calling functions: `if (button("Attack")) { doAttack(); }`
- No persistent widget state — layout and input handled in the same pass
- Excellent for debug tools, developer consoles, quick prototyping
- Low ceremony: no widget tree, no event system, no layout engine to maintain
- Dear ImGui is the standard reference implementation

### Retained Mode UI
- Widget tree built once, updated when state changes, rendered by framework
- Event-driven: button click fires callback, text field emits on-change
- Better for complex UI (inventory grids, skill trees, nested menus)
- Layout engines handle resizing, anchoring, and responsive design
- Higher upfront cost but scales better for large, interactive UI

### HUD Design
- HUD elements anchored to screen edges/corners — independent of world rendering
- Minimize HUD clutter: show only what the player needs at the moment
- Contextual HUD: display interaction prompts only when near interactable objects
- Health/resource bars: use color, shape, and animation — don't rely on color alone
- Fade or collapse HUD during cinematics and exploration for immersion

### Data Binding
- UI reads from game state, never modifies it directly — unidirectional data flow
- Observable properties or reactive bindings: UI auto-updates when underlying data changes
- Avoid polling game state every frame for complex UI — use change notifications
- Separate presentation model from game model — UI-specific formatting doesn't belong in gameplay code

### Localization
- All user-visible strings in external tables, never hardcoded — keyed by ID, not English text
- Design UI for text expansion: German is ~30% longer than English, CJK may be shorter
- Font atlases per language: Latin, CJK, Arabic, Devanagari require different glyphs
- Right-to-left (RTL) layout support: mirror UI, not just text direction
- Pluralization rules vary by language — use ICU message format or equivalent

### Input Handling
- Abstract input into actions: "confirm", "cancel", "navigate" — not "A button", "Enter key"
- Support multiple input methods simultaneously: keyboard+mouse, gamepad, touch
- Visual prompts match current input device — show gamepad icons when gamepad is active
- Focus/navigation system for gamepad: explicit focus order, wrap-around at edges
- Accessibility: support rebinding, text scaling, high contrast, screen reader hooks

## Gotchas / Anti-patterns
- UI code directly modifying game state — makes state changes untraceable
- Pixel-perfect layouts that break at different resolutions — use anchors and relative positioning
- Hardcoded strings: "Press X to continue" when the player is using a keyboard
- Immediate-mode UI for complex inventory with drag-and-drop — awkward state management
- Font rendering without subpixel positioning — blurry text especially at small sizes
- Not testing UI with long localized strings — layout breaks in German but looks fine in English
- Modal dialogs that block game input but not game simulation — time passes while paused

## References
- Dear ImGui: https://github.com/ocornut/imgui
- Game Programming Patterns — Observer (for UI binding): https://gameprogrammingpatterns.com/observer.html
- Figma/design system approach to game UI: concept of component reuse applies
- ICU Message Format: https://unicode-org.github.io/icu/userguide/format_parse/messages/
- GDC: "The UI Architecture of Destiny" (Bungie)
