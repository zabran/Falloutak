# Falloutak: the architecture (read this before searching the code)

A Paper 1.21.11 plugin (Java 21) for the tabletop game Fallouťák. One package, `cz.lollywood.falloutak`. Every rule is unit-tested
outside the game; the game itself is for the look and feel. The rules' text lives on the group's wiki, not here.

## The layers
1. **Rules, pure Java** (no server needed, the tests call them directly): `Rules`, `Combat`, `Data`, `Diseases`, `Conditions`,
   `Meals`, `Spoil`, most of `Character`, `Production`, `Population`, `Research`, `WorldMap`, `Tick`.
2. **State** (saved as JSON in `plugins/Falloutak/`, all through `Store.write` / `Store.save` / `Store.read`, so a file is written
   whole and an unreadable one is kept aside as `<name>.bad-<time>.json`): `Character` (one file each), `Base`, `Production`,
   `Population`, `Zones`, `Travel`, `Tick`, `Saves`.
3. **The game's surface** (Paper API): `FalloutakPlugin` (commands, events, the server clock), `Hud` (each character's hour),
   `Menus` / `Forms` / `SheetView` / `Works` / `Labs` (the pages), `Ui` + `Txt` (pages as data, laid out in columns), `Battle` /
   `Raiders` / `Arena` / `Path` (the battle), `Travel` / `MapView` / `Maps` (the world map), `Items` / `Storage` (items and chests),
   `Sfx` / `Narrator` / `Icons` / `Creatures` / `BmModels` (sound, voice, pictures, models).

## The main flows
- **A game hour of a character** (`Hud.hour`): the survival bars move by `Character.hourly` → the stages' HP → the sun → the radiation
  crisis and the glowing one → once a day the mutation episode and the carried food's spoiling (`Spoil.carried`) → healing
  (`Hud.heal`) → the radiation rolls, therapies, grafts → the lifetime rads → `Diseases.hour` (cancer's year, the sick nearby, every
  disease's race) → `Conditions.hour` (trench foot, scabies, latent tuberculosis, scurvy's HP) → gases, power armour, the heart's
  arrest → the timed effects count down.
- **The GM passes time** (the pass-time page in `Menus`): the groups work (`Roster.work`) → the base's people's factors by species
  and their group's Survival (`Meals.npcFactors`) → `Production.advance` runs the buildings hour by hour and, each new day, the water,
  the people's food, the soap and `dayHook` = `Meals.baseDay` for each character in the base (his own meals from the store,
  scurvy's day) → `Hud.pass` runs each character's hours.
- **A march on the world map** (`Travel.go`): `Travel.walk` steps by terrain and rolls the encounters every 4 hours → `Hud.pass` for
  the party → the weekly world tick (`Tick`) catches up → an encounter becomes a scene (talk, toll, go round, ambush) → a fight is
  played on a battle map (`Arena`) with `Battle` and `Raiders` → the field after it (`Loot`).
- **A battle turn:** the player spends AP in `Battle`; a shot or blow goes through `Combat` (to-hit, the d100, crits, damage after
  DT and DR); a hit on a player goes through `Battle.hitPlayer` (HP, Pain, bleeding, the wound's infection by its class:
  `Diseases.wound`); the enemies' turn is `Raiders` (the giant tick's bite also `Diseases.tickBite`). A gun takes its calibre's rounds (a
  rounds category a calibre); each magazine holds one load, whose damage modifier, penetration and fouling hit (`Battle.reload`,
  `Battle.ammoMods`; old round items map to their calibre by `Production.RENAMED`).

## The data
`src/main/resources/data/*.json` are GENERATED, never edited by hand, by the tools in the LollyWood-Lore repository (`_tools/`):
`fallout_catalogue.py` (item types, recipes, research, buildings, cards, designs, hauls), `fallout_part3_json.py` (species,
implants, chems, mutations), `fallout_diseases_json.py` (diseases, from `fallout_disease_sim.py`), `fallout_armour_json.py`,
`fallout_perks_json.py`, `fallout_traits_json.py`, `fallout_workbook_to_json.py` (the old workbook's tables). `weapons.json` alone is kept by hand:
the numbers of `05d` (the realism check `fallout_weapons_real.py`). Which tool writes a
file: its docstring, or `grep -l "<name>.json" _tools/fallout_*`. The world map's `worldmap.json` (the danger simulation's export)
holds hidden places: it lives only in the server's folder, never in the jar or this repository; the tests use the made-up
`src/test/resources/worldmap-test*.json`.

## Tests and build
`./gradlew build` (JDK 21): about 390 tests in three JVMs, ~40 s. One test class per rule area (`DiseasesTest`, `ConditionsTest`,
`MealsTest`, `ProductionTest`, `BattleTest`, ...); MockBukkit for the menus. Coverage only when asked: `./gradlew test
jacocoTestReport`. While working, run only the touched classes: `./gradlew test --tests '*MealsTest'`.

## The classes
The descriptions are kept by hand; `python -I -X utf8 tools/arch_index.py` refreshes the line counts and adds a new class with its
javadoc's first sentence marked NEW (then describe it here).

<!-- CLASSES -->
| class | lines | what it is |
|---|---|---|
| `Anomalies` | 789 | the anomalies, their artifacts and effects, the Black Rain storm |
| `Arena` | 754 | the battle maps of world-map encounters (terrain, size, placing the sides) |
| `Base` | 533 | the base's store of generic items (type, tier, rarity, condition), unlocks, the gene pool |
| `Battle` | 2260 | the turn-based battle: state, the player's actions and AP, hits on players, the battle's end |
| `BmModels` | 41 | the only class touching BetterModel's API (creature models) |
| `Character` | 2729 | one character's sheet and body: attributes, skills, survival bars, effects, armour, chems, mutations, grafts |
| `Combat` | 423 | to-hit, the d100, critical hits and their effects, damage after DT and DR (pure) |
| `Conditions` | 256 | the conditions with their own clocks: scurvy, trench foot, scabies, latent tuberculosis |
| `Creatures` | 160 | the game's creatures in the world (spawning, their sounds and motion) |
| `Data` | 935 | the rule tables loaded from `data/*.json` (species, effects, items, chems, genes, weapons ...) |
| `Diseases` | 608 | the diseases' race, catching, vaccines, the treatments and the tending check |
| `FalloutakPlugin` | 425 | the plugin's entry: enable/disable, `/sheet` and the other commands, the events, the server clock |
| `Forms` | 425 | the dialog forms: create a character, spend points, the level-up |
| `Hauls` | 266 | the prologue's last-minute hauls |
| `Hud` | 432 | each character's game hour and passing time, healing, the action bar |
| `Icons` | 129 | pictures in hover texts |
| `Items` | 545 | items in the pack: ids, giving and taking, armour as items, item stacks for the menus |
| `Labs` | 218 | the research pages |
| `Loot` | 337 | the field after a fight: bodies, gear, what can be taken |
| `MapView` | 184 | the world map drawn in Minecraft |
| `Maps` | 164 | the map worlds and their commands |
| `Meals` | 253 | eating and drinking: the pack's portions, the base's day per person, the base's people's factors |
| `Menus` | 1954 | every menu: actions, the patient's menu, pass time, the GM's buttons |
| `Narrator` | 165 | the narrator's lines on critical hits and failures |
| `Npcs` | 204 | the GM's NPC tools: a figurant's side (hostile, neutral, the party's), following a player, joining his faction, the fight where they stand |
| `Part3` | 308 | the species and modification commands (templates, genes, implants) |
| `Path` | 71 | how an enemy walks on the battle map |
| `Population` | 188 | the base's people and their groups (the workbook's Population and Factions) |
| `Production` | 1397 | buildings, jobs, recipes, power and water, the base's day (food, water, soap) |
| `Raiders` | 913 | the enemies' turn: choosing, moving, shooting, melee and bites |
| `Research` | 614 | research: inspiration, experiments, prototypes, the cards |
| `Roster` | 525 | `/npc`, `/pop`, `/group`: statblocks, the groups' XP and levels, lessons, work |
| `Rules` | 691 | the sheet's formulas: attributes, skills, checks (a comparison, never a roll), survival stages |
| `Saves` | 134 | saved games (the whole state copied and restored) |
| `Sfx` | 149 | every action's sound |
| `SheetView` | 465 | the character sheet as a text screen, its pages |
| `Spoil` | 116 | food spoiling by tier, rarity and place |
| `Storage` | 336 | the base's store as Minecraft chests |
| `Store` | 123 | character files and THE SAVING HELPER for every saved file |
| `Talks` | 306 | an encounter's talk: tolls, threats, allies |
| `Tick` | 386 | the weekly world tick (bases, lairs, the raiders' strength) |
| `Travel` | 768 | world-map travel: steps by terrain, the encounter roll, weather and season, the scene |
| `Txt` | 276 | text in columns inside a dialog (pixel widths), the sheet's number format `Txt.f` |
| `Ui` | 181 | pages as plain data (lines, inputs, buttons), shown as Paper dialogs or clicked in tests |
| `Works` | 417 | the production pages |
| `WorldMap` | 368 | the area's danger map (`worldmap.json`): sectors, hazards, bases, lairs |
| `Zones` | 281 | gas and anomaly zones in the world |
| **47 classes** | **24202** | |
<!-- /CLASSES -->
