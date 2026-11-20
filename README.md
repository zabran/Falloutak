# Falloutak

Fallouťák (the LollyWood group's post-apocalyptic RPG) inside Minecraft: a Paper 1.21.11 plugin (Java 21). The design lives in
LollyWood-Lore `_notes/Fallout/` (`NOW.md` first; the sheet's layout: `02d-SHEET-LAYOUT.md`).

**Version 0.40.0 (2026-11-20): the bound perks pick from lists and run; the melee families.**
- **Bound perks as a selection** (Marek: *"how will it work mechanically? Should we not make it a selection?"*): each of the
  seven picks from what the plugin knows, and the pick becomes part of the perk's name on the sheet (*Nemesis: Raiders*), which is
  what the game reads. Tag! the untagged skills · Signature Weapon your weapons at skill 100+ · Weapon Master the weapon skills
  at 150+ · Nemesis the kinds of foe and the world's powers (`data/foes.json`, the GM adds rows) · Old Friend the chems · My People
  the groups (your own first) · Home Turf the base and the areas the GM named when they were found (*Award XP → An area found*).
- **They run:** Nemesis +15 to hit and +25 % damage against its kind (the demo's raiders are *Raiders*); Signature Weapon +15 to
  hit, one crit degree better, no wear and no harm from a critical failure; Weapon Master −1 AP (at least 1) and +25 % damage
  with its family (the two +25 % add); Old Friend its chem's effect ×1.5 and no addiction roll. Home Turf and My People stay the
  GM's (no places in the plugin before the local map, no leadership bonus in play yet).
- **The melee families** (Marek: *"also unarmed. And I think technoweapons are technically also melee (most of them)."*): Unarmed,
  Knives, Hammers, Spears, Axes, Swords, Techno Weapons. **Any weapon skill** = Guns and the 14 combinations, so Athletics counts
  as Unarmed and Chemistry as Explosives (his *"athletics is unarmed ... chemistry is also explosives"*).
- 117 tests.

**Version 0.39.0 (2026-11-20): our perks; a new character from the group; lessons by level.**
- **Our perks** (Marek: *"please, add our perks. We already have almost all usable ones."*): the 111 perks of `02c` §2-§3 in
  `data/perks.json` (`_tools/fallout_perks_json.py` in LollyWood-Lore), each with its gate (a skill at a value, or any skill, any
  weapon skill, any science, a melee family; permanent values). At a perk level *Pick a perk* (Actions, the sheet's Traits &
  perks page, a group's page) lists the ones whose gate is met; parked ones (parts 7-8) are not offered; bound ones ask for the
  thing (Tag! for the skill). The sheet applies the numbers of 17 of them (Gain X, Educated, Toughness, Action Boy, Faster
  Healing, Rad Child, Lifegiver, Earlier Sequence, Thief, Sniper, Here and Now); the rest are the GM's until their rules run.
- **A new character from the group** (Marek: *"the player can create a new character in character creation, using the the group
  instead of template, pick his own traits, attribute values and perks + redo the last 2 levels (skill points)"*): when a
  character dies, *A new character from <group>* asks who of the group he becomes, then the creation pages with the group's
  statblock in place of the template: his own attributes and traits, XP = the pool / the headcount, the group's skill points of
  all but his last 2 levels, those 2 levels' points to spend again, then his perks. The tags stay the group's. A group keeps its
  statblock's points at each level from this version on.
- **Lessons by level, NPCs teach too** (Marek: *"I would base this on level, since it gives XP anyway. And I think NPC should be
  able to teach as well."*): a teacher of a higher level than the group (a player's character or an NPC statblock; a group's
  statblock teaches at its group's level), no subject; the week counts on the world's clock (the hours the GM passes for all).

**Version 0.38.0 (2026-11-20): the groups for every player; taking over a member; lessons; downtime work.**
- **No faction leader** (Marek: *"any player, it does not really matter who does faction management"*): *Actions → Groups* shows
  every group (its level, people, activities) and anyone spends a group's points there (or `/group spend`); `/group leader` is gone.
- **Work outside the base gives nothing yet** (Marek: *"these are all outside of base and most likely on local map. We still have no
  rules for that at all."*): scavenging, patrols and expeditions add 0 from the base's clock; any other work 1 XP an hour; idling,
  healing, sleep and sickness 0.
- **When a character dies for good**, *Actions* offers *Go on as one of <group>*: the player picks a member, who gets the group's
  statblock, XP = the pool / the headcount, his own row's traits and HP; he leaves the count with his share. The dead one is kept
  in `fallen/`.
- **Lessons** (*Groups → Lessons*): a character teaches a group a skill he knows better than its statblock; each student (a class of
  1 + Leadership / 10) gets 1 + Leadership / 25 XP an hour instead of work's 1, at most 8 lesson hours a group a game week; the
  teacher earns 1 XP an hour.
- **Downtime work** (*Actions → Work in the base*): 1 XP an hour while the GM passes time, into the group's pool too.

**Version 0.37.0 (2026-11-20): experience (part 6).**
- **The XP table, for the PCs and the groups** (Marek: *"this seems a lot better, accepted"*): a level L → L+1 costs
  250 × L × √L (level 2 at 250, 5 at 4 256, 10 at 27 762, 15 at 79 995); it replaced Fallout 2's 1000 × n (n − 1) / 2. Reaching
  a level adds its skill points to spend and names a perk level.
- **XP awards** (the GM's menu, *Award XP*, or `/xp`): enemies beaten (3.1 × HP^1.09 each, a peaceful end the same) shared by
  the team leader's Leadership, 1 / (1 + (n − 1) × max(0, 1 − L / 250)), the leader included; a passed check = its difficulty,
  at least 10, Doctor doubled; a non-generic area found = 100 × the areas found before, to each; research = 100 × tier ×
  rarity; a quest or any amount. The battle demo gives the raiders' kill XP when the fight is won.
- **The groups learn** (Marek: *"they should learn by doing"*): every PC's XP goes whole into his group's pool, and when the GM
  passes the world's time each member adds 1 XP an hour of work, 2 in danger outside (scavenging, patrols), nothing idle,
  asleep or sick. A group's level = the pool / the headcount on the same table; each whole level gives its statblock the skill
  points, and **the faction leader** (`/group leader <player>`, expected Spoke: *"The faction leader (Spoke) can manage all of
  them."*) spends them: `/group spend <group>`. A newcomer dilutes the level; points already given stay.

**Version 0.36.0 (2026-11-19): smoke and fog hide; the pollen allergy; all three falls.**
- **Smoke and the acid fog hide like the pollen** (Marek: *"yes, it should hide it in the same way when the target is in the
  cloud"*): a ranged shot into, out of or through a smoke or acid fog zone −10 × its strength (before, smoke only hindered a
  shooter standing inside it). The shooter's own smoke and a cloud on the line count once, the worst.
- **The pollen allergy** (Marek: *"if we have any alergy genes or something like that, this should trigger for the people
  alergy"*): the immunity genes that carry allergies (Broad HLA T2, Strong immunity T3) make the Trhač's pollen a gas for that
  character: sneezing and running eyes, P −2 and to hit −10 a round inside the cloud (× its strength; the numbers EXAMPLE); the
  big gas mask with a filter keeps it out. Everyone else still breathes it freely.
- **Sounds:** the knockback and both falls play all three of their recordings at random (Marek, round 4: *"ABC is great"*).

**Version 0.35.0 (2026-11-19): the Trhač after Marek's look; the pollen hides; the falls' pain cries.**
- **The Trhač, round 2:** the hide yellowish brown below, yellow on top; only short yellow blooms, close over the back; the
  body wider (2.6 blocks); **idle = a big yellow rock** (it lies in the field, the legs folded flat inside the body, the snout
  sunk into the ground, only the blooms stir); the walk and the charge step FORWARD (the knee lifted on the back swing before,
  a moonwalk; now checked by measuring the hoof's lift against its forward speed).
- **The pollen hides** (Marek: it should obscure range attacks the same way as smoke or fog does): the cloud is a zone
  `pollen` (a ball of 6 blocks for 3 game rounds); any ranged shot whose line of fire runs into, out of or through it gets
  smoke's −10 (the player's and the raiders'); nobody breathes it; GMs can place it like a gas.
- **Sounds:** the falls' cries are pain cries now (a man's low grunts by Michel Baradari, CC-BY 3.0, credited; a woman's hurt
  grunts by AuraVoice, CC0); the level-up is the picked C.

**Version 0.34.0 (2026-11-19): the Trhač.**
- **The Trhač** (`/creature spawn trhac`): a gigantic boar with rapeseed growing on its back, a Blockbench model written by
  `tools/models/trhac.py` (3.75 blocks at the shoulder, ~7.4 long; 14 rapeseed stalks, each swaying on its own), animations
  idle, walk, charge (the straight-line run, head low, the stalks blown back), gore (the tusks tossed up), pollen (it shakes
  itself, the stalks whip and a yellow cloud of pollen 6 blocks round it lasts 10 s), spawn (it stands up out of the field) and
  death. Its base is a hoglin (kept from turning into a zoglin), which is also its stand-in without BetterModel; its cry is
  Minecraft's angry hoglin, lowered. What the pollen does to aim and sight is not decided yet: it only hides it from view.

**Version 0.33.0 (2026-11-19): the sounds after round 2 of the listening test.**
- **Picks:** the turn's start and the other hand as picked; a bullet in flesh, a missed bullet, a knife in flesh and the
  footsteps play one of the liked recordings at random (several sounds in one event).
- **The falls cry out in the free pack itself:** a cry, then the body hitting the ground in two knocks, made like Fallout 2's
  from free recordings (new CC0 sources: *Male Grunt/Yelling sounds*, *Dying Voices - Female - RPG*, *Female screams*, *75 CC0
  breaking / falling / hit sfx*, *Thwack Sounds*); with the free pack the plugin adds no hurt voice, and a woman's knockback
  plays her own fall. New candidates for the falls and the level-up (two new starts before the chime, the chime longer).
- **The narrator:** 96 lines; one more groin crit, in both forms and in the second person.

**Version 0.32.0 (2026-11-19): the sounds after Marek's listening test; more narrator lines, some for the player hit.**
- **Sounds** (his verdicts, sound by sound): 19 keys play Minecraft's own sound again (the turn's end, lacking AP, the
  grenade's throw, the explosion, burning, the medical ones, the Pip-Boy, the Radmie...), the flare Minecraft's throw; a crit
  has no sound of its own any more (the hit's own sound and the narrator); a fall or a knockback now cries out (the man's or
  the woman's hurt voice with the body hitting the ground, unless Fallout 2's own fall plays); chems are heard (a pill, a
  drink or smoke as a drink, a needle as the Stimpak); the weapon drop is the critical failure's clang. New free candidates
  for the turn's start, the other hand, a bullet in flesh, a missed bullet, a knife in flesh, a fall, footsteps (four steps in
  a row) and the level-up; the first of each is in the pack. Two new CC0 sources (qubodup's *Impact*, Eldritch Grim's
  *Impacts/Gun/sfx*) and one OGA-BY 3.0 (*Fleshy Fight Sounds* by will_leamon: free with credit, credited in the pack).
- **The narrator, round 2:** 94 lines; new ones written freely from the ideas of the Czech fan translation's crit messages
  (never their words or Interplay's), and 17 in the second person that only the player who is hit hears ("You go down on one
  knee. Nobody is proposing.") while everyone else hears the third-person line.

**Version 0.31.0 (2026-11-19): the narrator comments on crits and fumbles; frostbite.**
- **The narrator** (Marek 11-19: Fallout's funny comments on criticals, spoken by the documentary narrator): after a critical hit
  or a critical failure, a comment picked by what it did (killed outright, the heart stopped, knocked out, blinded, a leg or an arm
  crippled, the weapon dropped, knocked down, a lost turn, through the armour, else the body part hit; for a failure: on fire, hit
  himself, the weapon destroyed, someone else hit, a fall, the weapon dropped, the ammo lost). 56 lines, OUR OWN text
  (`src/main/resources/data/narrator.json`, nothing taken from Fallout's messages), a woman's form for the lines that need one,
  voiced by `tools/narrator.py` (Kokoro-82M, Apache-2.0, the British voice `bm_lewis` at 0.92) into the public pack. Everyone
  near reads it in the chat; a player with the pack also hears it. `/sounds narrator off|on`.
- **Frostbite:** leaving Deadly Hypothermia alive now leaves the workbook's own Frostbite (HP Max −3) for good, the same way
  surviving Fatal Radiation leaves its scar (Marek 11-19: *"agreed. Similar to frostbite."*).

**Version 0.30.0 (2026-11-19): free sounds, a longer Radmie that strikes from above, surviving Fatal Radiation.**
- **Free sounds in the public pack** (`tools/free_sounds.py`, 47 of the 56 keys): CC0 recordings (Kenney's Impact, Sci-fi,
  Interface, RPG, Digital and Jingles packs; OpenGameArt's Gunshot sounds and Handgun Reload) and a Geiger counter made from
  nothing; sources in `pack/FREE-SOUNDS-CREDITS.md`. A player with the pack (from the server, or `/pack on`) hears them; the voices
  (hurt, dying, death), the cold and the Trhač stay Minecraft's. Fallout 2's own pack, if a player has it, still wins.
- **The Radmie, round 2:** about 18.6 blocks long and 6 x 5 px thick (a human is 10 x 29); it rises about 3.4 blocks to look over
  the rubble (`rise`), strikes down onto a target 3 blocks further (`lunge`) and snaps back, the back half on the ground throughout.
- **Surviving Fatal Radiation:** RadAway (50 + Medicine rads a dose) brings it down while Stimpaks outrun the drain; whoever comes
  out of it alive keeps a permanent scar, Endurance −1 and HP Max −5 (EXAMPLE numbers).

**Version 0.29.0 (2026-11-19): Pip-Boy pictures, the first creature model, Fatal Radiation.**
- **Pip-Boy pictures in the sheet's hover texts** (attributes, skills, survival values): our own mascot, a lanky shelter warden
  with glasses and a steel helmet (not Bethesda's Vault Boy), drawn by FLUX.2 klein and turned into green screen pictures by
  `tools/cards.py` (`pack/cards.json`, a bitmap font `falloutak:cards`). Shown to a player whose client loaded the server's pack,
  or who types `/pack on` (a pack installed by hand); everyone else keeps the small item sprites. Traits and perks keep the sprites.
- **The Radmie**, the first creature with its own model and animations (idle, walk, lunge, hide, spawn, death): a Blockbench
  model written by script (`tools/models/radmie.py` → `models/radmie.bbmodel`; `tools/models/bbmodel.py` renders previews),
  shown through **BetterModel 2.2.0** (the last release for Java 21; 3.x needs Java 25): put the jar in `plugins/`, the
  `.bbmodel` in `plugins/BetterModel/models/`, turn `player-animation` and the legacy models off in its `config.yml`, then
  `tools/build_pack.py --bettermodel plugins/BetterModel/build.zip` merges its models into the one public pack. GMs:
  `/creature spawn radmie`, `/creature play <animation>`, `/creature remove`. Without BetterModel the Radmie is a big silverfish.
- **Fatal Radiation Poisoning** can push the healing rate below 0: the character then LOSES that many HP every 8 hours, resting
  or not. Every other stage stops at 0 (rest heals nothing).

**Version 0.28.0 (2026-11-19): sounds, pictures, overlays.**
- **Every action has a sound** (`src/main/resources/data/sounds.json`, 56 keys: shots, hits, misses, reloads, stances, grenades,
  blasts, bodies, turns, the Geiger counter, gas, shocks, the store). Without anything installed they are Minecraft's own sounds.
  **Fallout 2's sounds** are Interplay's, so they are never in this repository or its releases: `tools/fallout2_sounds.py
  <your master.dat>` builds a PRIVATE pack (namespace `falloutak_f2`) from a Fallout 2 you own; put it in your resource packs and
  type `/sounds fallout2` (`/sounds female` for a woman's body sounds, `/sounds test <key>`, `/sounds list`).
- **Item pictures**: `tools/build_pack.py` builds the public resource pack (`pack/`: our own 32 px icons, drawn with FLUX.2 klein 4B
  and reduced by `tools/pixelize.py`); items carry custom-model-data strings, so without the pack they keep their vanilla look.
  The server sends the pack on joining (`config.yml`: `resource-pack.url` and `sha1`, e.g. a GitHub release asset).
- **Pictures in the sheet's hover texts** (attributes, skills, traits, perks, survival values): sprites of the game's own items
  (Minecraft 1.21.9's object text component), no pack needed.
- **Overlays**: the frost while a Hypothermia stage lasts, a red screen edge for radiation sickness and low HP.

**Version 0.7.0 (2026-11-14): the data store, step 2: NPCs, the population, the factions** (the workbook's NPC, Population and
Factions tabs, so it can be archived). Anyone may look; changing needs `falloutak.gm` (operators).
- `/npc [list]`, `/npc edit <name>`: an NPC statblock is a character sheet saved as `plugins/Falloutak/npcs/<name>.json` (the
  Factions tab's own note: *"load a save do NPC sheetu"*). While you edit it, `/sheet` and its forms work on the NPC; `/npc save`
  writes it, `/npc done` saves it and gives you your own sheet back (quitting does the same). `/npc show <name>`, `/npc delete <name>`
  (renamed `.deleted-<time>`, never lost).
- `/pop [list]`, `/pop add <name> <faction> [species]`, `/pop set <name> <field> <value>`, `/pop remove <name>`: the base's people with
  the tab's columns (HP, state, species, faction, armour, two weapons, activity, traits, born); a new member takes his HP from the
  faction's statblock (an NPC of the faction's name: one statblock per faction). The first start writes the tab's two rows.
- `/faction [list]`, `/faction add <name>`, `/faction xp <name> <amount>`: members counted from the population; the level is the
  tab's formula, Fallout 2's level table on the XP per member. How a faction learns is part 6's open question: XP is only recorded.
- **Fixed:** the survival maxima are the workbook's own (water and food + Survival / 2, not Athletics; poison 40 + 3 x the
  species' HP bonus; cold and heat the Survival points + (bonus + 3) x 4): a human's poison maximum is 85, cold and heat 72.

**Version 0.6.0 (2026-11-14): the fight ends, weapons wear (Fallout: New Vegas), burning as Fallout 2.** The demo ends by itself
when no raider can fight (dead, dying, his gun destroyed or broken; Marek 11-14). Every weapon has New Vegas's item health (the
10mm Pistol 80, so ~395 rounds): a gun loses 0.2 a round fired, its damage falls from full at 75 % condition to half at 0, it
breaks on its last point; a fumble's "weapon damaged" takes a tenth of it (EXAMPLE); `/sheet repair` uses one of the two repair
kits on the weapon in hand (4 AP, EXAMPLE; +10 to +30 % by Crafting, New Vegas's table; Mr. Fixit doubles it); the action bar
shows the condition. The raiders' guns wear the same way. Catching fire (a fire weapon only) is Fallout 2's: the weapon's own hit
on the shooter as fire damage (Hyperthermia x3, the survival note), no burning over time.

**Version 0.5.0 (2026-11-14): the raiders shoot back** (part 5, `05-BATTLE.md`). `/sheet battle` starts a fight with two
raiders (the workbook's *Nájezdník 2*, EXAMPLE gun and skill). The player acts first; the clock ends his turn, then each raider
who can act takes his own, shown step by step: gets up (3 AP), picks up a dropped gun, reloads, shoots when he has a clear line in
range, else walks toward a spot that gives him one (and steadies after walking, crouches with AP left). His shots use the same
rules as the player's (the player's AC = Agility + stance, the light at the player): a hit costs HP, Pain and bleeding, a crit
knocks down, cripples, blinds or takes a turn, knockback pushes; a death in the demo ends it and gives the character back as it
began. Marek's rulings of 11-14 (round 2): a target down +10 and lying like a crawler (+4 AC, +20 within reach), a roll of 100
always misses, Jinxed (Fallout 2: half of all misses fumble), a fumble damages the weapon's condition (−10, EXAMPLE) and sets the
shooter alight only with a fire weapon, the light still at any distance (`Combat.lightFreeWithin` for the balancing).
Since 0.4: the hit roll term by term in chat and live in the action bar, crits, critical failures, F2's burst, the frag grenade,
the Stimpak, and every character saved as JSON (`plugins/Falloutak/characters/<uuid>.json`).

**Version 0.2.0 (2026-11-14): the EXAMPLE, after Marek's first look** (`02d` §5b). One sample character per player:
- `/sheet` (or `/sheet skills`, `weapons`, `traits`, `body`, `survival`, `combat`, `achievements`): the sheet as a TEXT screen (a
  dialog, like the level-up form): every value with its breakdown written out, the workbook's own notes under the attributes,
  skills, weapon skills and survival stats, the pages as buttons. `/sheet icons` = the 0.1 chest screen, for comparison.
- `/sheet create`: the creation form in three dialogs (name, template, SPECIAL sliders checked to sum 40 · three tags · traits).
- `/sheet spend` or the Skills page's button: the level-up form, a slider a skill, priced 1 / 2 / 4 points a %.
- The survival sidebar (only the stats past stage 0, as ruled; `/sheet hud` shows all) and the action bar; `/sheet demo` runs a
  clock of one game hour a second.
- `/sheet hit <n> [crit|stab]`: a wound. HP −n, Pain +3 a HP lost and +25 on a crit (the workbook's note on Pain); a crit opens a
  weak bleeding (2 % of Blood a round, +1 % a further cut), a stab a strong one (5 %): Andor's rule. Out of battle a round passes
  every 6 s. `/sheet bandage` stops it (in battle: the whole turn). The dying state runs from 0 HP to −Endurance.
- `/sheet battle`: the battle controls (right click = mode, left click AT AN ENEMY = fire, shift = stance, double tap = prone,
  F = the other hand, E = the pack, reload automatic, the clock = end turn). Two raiders stand ahead (since 0.5.0 they take their
  turns and shoot back): an attack needs an enemy in sight within the weapon's range (the pistol 25 blocks, the knife the next block), else it is
  refused and costs nothing. Walking costs AP by stance and walking back refunds it; **a stance change is movement too**: shift
  back on the same block, with no step since, and its AP comes back. Your inventory is put away and given back. Best in survival
  mode (`/gamemode survival`): creative's inventory is a different screen.
- `/sheet reset`: the sample character again.

**The rules' tables come from the Google workbook** (Marek 11-14: he wants to archive it): `src/main/resources/data/species.json`,
`effects.json`, `items.json`, `factions.json` from its cell dump (formulas kept beside the values), and `notes.json`, every cell
note of every tab (the skills' and stats' descriptions, the rules on Pain and Blood, the stances), from the Drive export; all
written by LollyWood-Lore `_tools/fallout_workbook_to_json.py`. The templates, the survival stages' attribute effects (Paralyzed by
Pain = Agility −99, so no AP) and the 10mm Pistol are read from them.

Build: `./gradlew build` (JDK 21) → `build/libs/Falloutak-<version>.jar`, beside nothing else (no dependencies).
