# Navigation checkpoint regression checks

Run `python tests/navigation/run.py` from the repository root. Uses cached Kotlin compiler jars and the Java2D Android bitmap stub; no emulator or game interaction.

Coverage:
- Main HUD at multiple aspect ratios, welfare supply, two real dungeon captures, warp marker.
- Login and loading are unknown states, so no navigation clicks are chosen.
- Expanded menu controls on one frame and mail -> menu -> guild without closing/reopening the menu.
- Existing welfare, guild, kitchen, island and trial pages are reused.
- A synthetic Must-do hub with a pale bottom strip remains HUB; an unlabelled pale strip stays UNKNOWN.
- A synthetic high-confidence menu pasted onto a real dungeon screenshot never produces HUD or expanded-menu state.
- Exit confirmation wins over the dungeon clock visible behind it.

The application keeps task order and existing in-page steps. Global classification runs at task boundaries and navigation transitions, not for every ordinary button. HUD, expanded menu and dungeon scene need three stable frames; other destination pages are handed to their task after one recognized frame, while noncritical navigation actions need two. Welfare pages require the fixed title plus page structure, then use a fast path instead of scanning unrelated templates. A pale bottom strip alone never establishes Welfare or Supply; two fixed left tabs can instead establish the Must-do hub. Click points come from the current frame. An unknown state only waits. Known failed transitions have bounded retries and one recognized route back to HUD; unresolved navigation saves diagnostics and ends the chain after the engine's retry/reconnect handling.

These offline fixtures do not cover every live popup or account UI variant. APK compilation and these checks do not substitute for emulator validation.
