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
- **A game hour of a character** (`Hud.hour`): `Character.readAttributes` (the hour's maxima from the current attributes, `lagAttrs`; the bars and HP keep their share, 0.115.0) → the hour's body heat once (`Character.thermo` → `Rules.thermo`: the air of `Travel.air`, set by the GM, against the outfit's comfort interval moved down by `Character.activity`; 04 DRAFT 2, 0.114.0) → the survival bars move by `Character.hourly` (the sweat off the water, food by activity and the Starvation stage, sleep only to the maximum minus `sleepDebt`; `sleepDay` each morning) → the stages' HP → the sun → the radiation
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
  The person behind the weapon (05d rounds 3-5): `Battle.handBonus` (Strength by GURPS, the skill; Unarmed twice), `piercing`
  (Unarmed 150: the DT a fifth), `guardAc` (AC against blows from the weapon's skill), `recoilBonus`, `reloadAp`, `unaware` (the crit
  chance x2); the shock `Battle.shock` (an NPC) and `Battle.shockPlayer` (a player: his armour's Isolation, AP lost next turn); the Chi Master perk `Battle.chiLevel`, `chiDamage`, `reach`, `chiName` (Occultism, the hidden 16th skill of
  `Rules.SKILLS`, shown as `Rules.hiddenWord`; its gate two conditions, `Data.Perk.also` + `Rules.gateMet`) and from Occultism 200
  its Kamehameha (`Battle.KAME`, `kamehameha`, the beam `onBeam` + `beam`); a melee weapon on a Power cell (`Weapon.cell`, `CELL_DRAW`:
  the Plasma saber, 05d round 7); black powder and the AP rules (05d round 7): `Weapon.reload`, `lock`, `powder`, `pierce`,
  `Battle.reloadAp` (the skill's scale, fouled +50 %), `misfire`, `fouled`, `turnAp` (unused AP carried), `partPay` + `reloadStep`,
  `cleanStep` (long actions in parts), the smoke (`State.smokeRound`, read by `Raiders`).
  The armour's renamed sets resolve by their old name (`Data.ARMOUR`'s `get` + `ARMOUR_WAS`, armour.json "was"); a piece's Strength for
  carrying only (`Armour.carryStr`, `Character.carryStrength`: the load exoskeleton, 05c §12).
  Shields (05c §11): `Data.SHIELDS` (`data/shields.json`), the off hand from his own kit (`State.shield`, `shieldHealth`, written back to
  the item after the fight), facing = his yaw as his turn ends (`State.faceYaw`), `Battle.frontal`, `shieldHit`, `shieldTake` (called by
  `Raiders` on a shot and a blow before his armour), `shieldObscures` (a holed shield: -10); the unused AP saved at the turn's end
  (`State.carry`).
  The throws, blows and darts (05d round 7): `Weapon.entangle` (bolas, net: `Target.entangled`, freed in `Raiders.plan` by `Battle.tearsFree`),
  `throwsName` (the spear-thrower's javelins), `noGuard` (the war flail), THE SLEEP DARTS (0.111.0, `04j` §7h: `Battle.dose` reads the load,
  `sleepRounds` by the body (`PERSON_HP`, `LARGE_HP`: only the Tier IV knockout takes the large), `dart` sets `Target.sedatedAt`, asleep at once or
  `drowsy` (AP −1 in `Raiders.plan`/`cross`); the double dose's `breathRisk` sets `Target.breathStopAt`, `Raiders.breathEnds` kills him;
  on the field `Loot.Body.breathless`, `Loot.settleBreath` (the field's clock), `Loot.naloxone` from the pack). THE TTR (0.112.0, Marek 11-28: one row's items are
  interchangeable in the base, so they must be worth about the same, or the type is only parts for an assembly): the darts are an
  assembly of Chems and Chemicals rows (no chem of their own); a design's part may carry its own rarity (`Production.Part.rarity(d)`,
  used by every `Base` assembly loop: the Opium darts take Chems II R2).
  The traps (05d §7c): `Data.TRAPS` (`data/traps.json`), the skills Traps, Mines, Alarms (`Rules.COMBOS`), `Battle.Trap` in `Fight.traps`,
  `setTrap` (`/sheet trap`), `trapAt`, `knownTraps` (blocked cells in `Raiders.taken`), `spots`, `inCone`, `spring`; `Raiders.trapCut` on the
  raider's and the beast's walk and on a hunted animal's flight; a pit's victim loses its next turn (`Target.inPit`).
  Dug before a fight (a pit, a deadfall): `Travel.dig` (`/travel dig`, the scene's Dig buttons, only before an ambush seen in time or a
  hunt; `Travel.Dug` in `Travel.dug`), laid by `Arena.layDug` (half-way to them, or past the hunted animals short of the edge; `standNear`,
  `toEdge`) into `Fight.traps` when the battle map starts.
  The pavise set down (05c §11): `Battle.pavise` (`/sheet pavise`, 4 AP down or up), `State.wall` (`Battle.Wall`, a display entity),
  `behindWall`, `wallCovering`, `wallTake` and `wallObscures` (called by `Raiders` on a shot, before a held shield), `wallCells` (in
  `Raiders.taken`), `removeWall` (taken up, or at `stop`).
  The dart's sleep: `Target.asleep` (out of the fight: `inFight`), on the field `Loot.Body.asleep` (taken alive: `Loot.takeAlive`), one
  that ran off darted a trail (`Loot.Body.trail`, `Loot.track`: Survival `TRAIL_NEED`, found = its kill XP, `Loot.team`).
- **A medicine used** (04i round 1): every dose an item (`thing:Medicine|tier|rarity|name` or a chem); `Menus.treatDisease` asks
  `Meds.forAct` for the action's items (an oxygen cylinder for 10 hours, diazepam, honey, the antitoxin or the crude antiserum, the
  rabies series, an anaesthetic for the knife: chloroform can stop the heart) and runs an antibiotic course from the doses carried
  (`Meds.ABX`: family, strength, the pre-war stores' age; fewer than the course = `Diseases.Case.abxDays`, a short course);
  `Menus.firstAid`'s dressing takes `Meds.dressing`; `Meds.useSelf` (the Pip-Boy, a right click): vitamin C (`Character.vitaminAt`,
  read by `Meals`' scurvy day), a blood bag, antivenom, rehydration salts, iodide, the vaccines. A stack weighs its vessel and a
  hundredth of a kilo a further dose (`Meds.stackKg` in `Items.weight`). Chems: methadone's `Data.Chem.relieves`, ibuprofen's and
  aspirin's `bleeding` (in `Hud.bleedRound`), alcohol's `cold_gain` (in `Character.hourly`).
- **The chems round 2-3** (04i §6, 04j §7, 0.107.0): the injectors `Meds.inject` (the Stimpak stops every bleeding, the Super Stimpak
  and the Hypo HOLD HP: `Character.hold`, `healReal`, `releaseHeld` when the "held_hp" effect's hour ends in `Hud.hour`, HP back to
  min(now, `heldReal`)); `Meds.traumaPack` (Medicine 40), `atropine` (Medicine 50), `transfuse` (fresh whole blood), the Blood Pack
  and Virex (`useSelf`), Lysin (`ABX`), `keepStronger` (one slot, the stronger kept). The chems' survival keys read by
  `Character.hourly`: `water_need`, `food_need`, `sleep_need` (Mentats), `heat_per_hour` (at work), `cold_gain`/`heat_gain`,
  `rest_double` (Nightcap, also `Hud.heal`); `sleep_stages` read as the strongest (`maxOther`) by `stages` through `Rules.better`
  (`tolerant`: none in its own dependence, in `dose`); `Character.breathingStage` (an "opioid" with a second "depressant": unconscious,
  `breathRound` takes Oxygen, `breathHelpUntil` = a medic's breath, `Menus.BREATH_HELP_HOURS`); `dose` adds the slow onset in the
  cold or dry (`slowStage`, an "onset:" effect) and runs `applyNow` (the "now_*" keys: food, poison, the O-Shot's `oxygenExtra`,
  naloxone ending the opioids, an addict into withdrawal); Fixer's `withdrawal_off` (`fixerOn` in `other` and `attr`), Hydra's
  `mend_limb` (in `Hud.hour`), propranolol's `reboundHour`, Psycho's `no_knockdown` (in `Battle.hitPlayer`), Virex's
  `viral_immunity` (in `Diseases.hour`). Tests: `ChemsRoundTwoTest`.
- **0.108.0** (Marek 11-27 on 0.107.0's gaps): the splint (`Character.splints`, `splint`, `splintHour` in `Hud.hour`: a splinted broken
  leg knits in `SPLINT_HOURS`, an unsplinted one never by itself; `trimSplints` when a leg mends another way; `Meds.splint` at
  Medicine 20, the Trauma pack splints one leg; `Menus.splintWith`); Cateye's `light_sensitive` (`Character.lightHurts`: `inSun` or
  `flashUntil`, read in `attr`; `Battle.flash` at both blasts); nicotine's `cold_hands` (`smokerCold` in `stageHour`: the frostbite
  from a Critical cold); FALLING ASLEEP (0.110.0, `04j` §7g: `Character.keptAwake` says what keeps him awake in a rest, `lyingAwakeHour` makes that hour gain no Sleep while still counting as rest, `freezingHour` drops a Critical/Deadly cold body asleep on a failed Endurance check; `sleep_aid` (valerian, hops) takes the lighter causes off, a `depressant` all of them); THE FIGHT'S END (0.110.0: `Battle.Target.outOfIt` adds the knocked out and the netted to the dead, dying and asleep, so the fight ends and the living count as SURRENDERED: `Loot.Body.surrendered`, taken alive or finished on the field); THE BELT (`Battle.BELT_FIRST`-`BELT_LAST`: 0.109.0: what the player put on hotbar keys 3-6 BEFORE the fight is the belt
  (`beltKind`: Stimpaks and grenades counted, a chem, injector or self medicine kept as itself), the counted kinds he did not place take
  `BELT_HOME` or the first free slot; a free slot takes a real item dragged from the pack; `useBelt` (`beltUsable`) for `BELT_AP`,
  `settleGear` takes the "item:" uses). Tests: `BeltSplintTest`.

## The data
`src/main/resources/data/*.json` are GENERATED, never edited by hand, by the tools in the LollyWood-Lore repository (`_tools/`):
`fallout_catalogue.py` (item types, recipes, research, buildings, cards, designs, hauls), `fallout_part3_json.py` (species,
implants, chems, mutations), `fallout_diseases_json.py` (diseases, from `fallout_disease_sim.py`), `fallout_armour_json.py`,
`fallout_perks_json.py`, `fallout_traits_json.py`, `fallout_workbook_to_json.py` (the old workbook's tables). `fallout_weapons_real.py` writes `weapons.json`
(the numbers of `05d`: the realism check, every type Tier I-IV, the models). Which tool writes a
file: its docstring, or `grep -l "<name>.json" _tools/fallout_*`. The world map's `worldmap.json` (the danger simulation's export)
holds hidden places: it lives only in the server's folder, never in the jar or this repository; the tests use the made-up
`src/test/resources/worldmap-test*.json`.

## Tests and build
`./gradlew build` (JDK 21): about 420 tests in three JVMs, ~40 s. One test class per rule area (`DiseasesTest`, `ConditionsTest`,
`MealsTest`, `ProductionTest`, `BattleTest`, ...); MockBukkit for the menus. Coverage only when asked: `./gradlew test
jacocoTestReport`. While working, run only the touched classes: `./gradlew test --tests '*MealsTest'`.

## The classes
The descriptions are kept by hand; `python -I -X utf8 tools/arch_index.py` refreshes the line counts and adds a new class with its
javadoc's first sentence marked NEW (then describe it here).

<!-- CLASSES -->
| class | lines | what it is |
|---|---|---|
| `Accessories` | 228 | the weapons' accessories (05d §5): their effects, what fits what, `/sheet fit`, kept on the weapon's item |
| `Anomalies` | 789 | the anomalies, their artifacts and effects, the Black Rain storm |
| `Arena` | 826 | the battle maps of world-map encounters (terrain, size, placing the sides) |
| `Base` | 533 | the base's store of generic items (type, tier, rarity, condition), unlocks, the gene pool |
| `Battle` | 3202 | the turn-based battle: state, the player's actions and AP, hits on players, the battle's end |
| `BmModels` | 41 | the only class touching BetterModel's API (creature models) |
| `Character` | 3081 | one character's sheet and body: attributes, skills, survival bars, effects, armour, chems, mutations, grafts |
| `Combat` | 423 | to-hit, the d100, critical hits and their effects, damage after DT and DR (pure) |
| `Conditions` | 256 | the conditions with their own clocks: scurvy, trench foot, scabies, latent tuberculosis |
| `Creatures` | 160 | the game's creatures in the world (spawning, their sounds and motion) |
| `Data` | 991 | the rule tables loaded from `data/*.json` (species, effects, items, chems, genes, weapons ...) |
| `Diseases` | 614 | the diseases' race, catching, vaccines, the treatments and the tending check |
| `FalloutakPlugin` | 440 | the plugin's entry: enable/disable, `/sheet` and the other commands, the events, the server clock |
| `Forms` | 425 | the dialog forms: create a character, spend points, the level-up |
| `Hauls` | 266 | the prologue's last-minute hauls |
| `Hud` | 458 | each character's game hour and passing time, healing, the action bar |
| `Icons` | 129 | pictures in hover texts |
| `Items` | 553 | items in the pack: ids, giving and taking, armour as items, item stacks for the menus |
| `Labs` | 218 | the research pages |
| `Loot` | 431 | the field after a fight: bodies, gear, what can be taken |
| `MapView` | 184 | the world map drawn in Minecraft |
| `Maps` | 164 | the map worlds and their commands |
| `Meals` | 262 | eating and drinking: the pack's portions, the base's day per person, the base's people's factors |
| `Meds` | 373 | the medicines as items (04i): what each disease action, first aid and the Pip-Boy use up; antibiotic courses by dose; a dose's weight; the injectors and held HP, the Trauma pack, atropine, fresh blood (04i §6, 04j §7) |
| `Menus` | 2073 | every menu: actions, the patient's menu, pass time, the GM's buttons |
| `Narrator` | 165 | the narrator's lines on critical hits and failures |
| `Npcs` | 204 | the GM's NPC tools: a figurant's side (hostile, neutral, the party's), following a player, joining his faction, the fight where they stand |
| `Part3` | 308 | the species and modification commands (templates, genes, implants) |
| `Path` | 71 | how an enemy walks on the battle map |
| `Population` | 188 | the base's people and their groups (the workbook's Population and Factions) |
| `Production` | 1402 | buildings, jobs, recipes, power and water, the base's day (food, water, soap) |
| `Raiders` | 1014 | the enemies' turn: choosing, moving, shooting, melee and bites |
| `Research` | 614 | research: inspiration, experiments, prototypes, the cards |
| `Roster` | 525 | `/npc`, `/pop`, `/group`: statblocks, the groups' XP and levels, lessons, work |
| `Rules` | 766 | the sheet's formulas: attributes, skills, checks (a comparison, never a roll), survival stages |
| `Saves` | 134 | saved games (the whole state copied and restored) |
| `Sfx` | 149 | every action's sound |
| `SheetView` | 482 | the character sheet as a text screen, its pages |
| `Spoil` | 116 | food spoiling by tier, rarity and place |
| `Storage` | 336 | the base's store as Minecraft chests |
| `Store` | 127 | character files and THE SAVING HELPER for every saved file |
| `Talks` | 306 | an encounter's talk: tolls, threats, allies |
| `Tick` | 386 | the weekly world tick (bases, lairs, the raiders' strength) |
| `Travel` | 845 | world-map travel: steps by terrain, the encounter roll, weather and season, the scene |
| `Txt` | 276 | text in columns inside a dialog (pixel widths), the sheet's number format `Txt.f` |
| `Ui` | 181 | pages as plain data (lines, inputs, buttons), shown as Paper dialogs or clicked in tests |
| `Works` | 417 | the production pages |
| `WorldMap` | 368 | the area's danger map (`worldmap.json`): sectors, hazards, bases, lairs |
| `Zones` | 281 | gas and anomaly zones in the world |
| **49 classes** | **26781** | |
<!-- /CLASSES -->
