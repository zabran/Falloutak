package cz.lollywood.falloutak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The data store, step 2: the workbook's {@code Population} and {@code Factions} tabs (Marek 11-14: the plugin holds all data, *"I
 * would like to get into point that I can archive the Google sheet and never return to it."*). Population = the base's people, one
 * row each with the tab's columns; a group's statblock is an NPC sheet of the group's name ({@code npcs/<name>.json}, the
 * Factions tab's own note: *"výběr characteru viz character - load a save do NPC sheetu"*), so every member uses it (Marek's brief
 * for part 6: one statblock per group). Both live in {@code plugins/Falloutak/population.json}, written whole like a character.
 * Pure Java: the tests run it. **Part 6 (RULED 11-20, `06-GROUPS.md`):** a group's pool gains 1 XP a member-hour of work, 2 in
 * the base and 100 % of its PCs' XP; its level = the pool / the headcount on the PCs' own table; any player spends its points (Marek
 * 11-20: *"any player, it does not really matter who does faction management"*).
 */
final class Population {
    /** A person of the base: the workbook's Population columns. */
    static final class Person {
        @com.google.gson.annotations.SerializedName(value = "group", alternate = {"faction"}) String group = "";
        String name = "", state = "", species = "Human", armor = "", weapon1 = "", weapon2 = "", activity = "Idling";
        int hp, hpMax, born;
        List<String> traits = new ArrayList<>();
    }

    /** A group: the workbook's Factions columns that are not derived (its count comes from the people). */
    static final class Group {
        String name = "";
        double xp;
        /** Premium templates unlocked for every member at 2 x the CoS points (03b §2b, RULED 11-16). */
        List<String> unlocked = new ArrayList<>();
        /** The whole level its statblock has been given the skill points of (a diluted group earns its next level later). */
        int statLevel = 1;
        /** Lessons (06 §2.5): the game week (the world's hours / 168) and the lesson hours already given in it (at most 8). */
        long lessonWeek = -1;
        int lessonHours;
        /**
         * The statblock's skill points as they were when it reached each level, before that level's points were spent: a new PC from
         * the group keeps the points of all but his last 2 levels and spends those again (Marek 11-20).
         */
        java.util.Map<Integer, double[]> pointsAt = new java.util.TreeMap<>();
    }

    /** The world's clock: the game hours the GM has passed for everyone (the lesson week counts on it). */
    double worldHours;
    /** The non-generic areas the players have found (06b §5.4.5: the next one gives each PC 100 × this). */
    int areasFound;

    final List<Person> people = new ArrayList<>();
    @com.google.gson.annotations.SerializedName(value = "groups", alternate = {"factions"}) final List<Group> groups = new ArrayList<>();

    /**
     * A group's level = the pool / the headcount on the PCs' own table (06 §2.1, RULED 11-20: *"it is usable and simple"* and *"yes,
     * leader players can optimize the groups just like player characters"*; it replaced the workbook's Fallout 2 formula).
     * Fractional (the part above the whole level is the way to the next); 1 with no XP or no members.
     */
    static double level(double xp, int count) {
        if (count <= 0 || xp <= 0) return 1;
        return Rules.levelExact(xp / count);
    }

    /** The XP a member needs for level n: the PCs' table. */
    static double xpForLevel(int n) { return Rules.xpForLevel(n); }

    /** Activities that are not work (06 §2.1: *"Idling, sleep, sickness add nothing"*). [the list mine] */
    static final java.util.Set<String> RESTING = java.util.Set.of("", "idling", "idle", "healing", "sleeping", "sleep", "sick", "resting", "rest");
    /**
     * Work OUTSIDE the base (Marek 11-20: *"these are all outside of base and most likely on local map. We still have no rules for
     * that at all."*): nothing from the base's clock until the local map has its rules (part 9). [the list mine]
     */
    static final java.util.Set<String> OUTSIDE = java.util.Set.of("scavenging", "scavenge", "patrol", "patrolling", "wasteland", "expedition", "scouting", "hunting");

    /** A member-hour of this activity in the base: 1 XP, or 0 resting or outside. */
    static int workXp(String activity) {
        String a = activity == null ? "" : activity.trim().toLowerCase(Locale.ROOT);
        return Rules.workXp(!RESTING.contains(a) && !OUTSIDE.contains(a));
    }

    /** The members of a group. */
    List<Person> membersOf(String group) {
        List<Person> l = new ArrayList<>();
        for (Person p : people) if (p.group.equalsIgnoreCase(group)) l.add(p);
        return l;
    }

    /**
     * Hours of work pass (the GM's *Pass time* for everyone): each member adds his activity's XP an hour to his group's pool.
     * Returns the XP each group gained (only those that gained).
     */
    java.util.Map<String, Double> work(double hours) {
        java.util.Map<String, Double> got = new java.util.LinkedHashMap<>();
        if (hours <= 0) return got;
        for (Person p : people) {
            int x = workXp(p.activity);
            Group g = group(p.group);
            if (x == 0 || g == null) continue;
            g.xp += x * hours;
            got.merge(g.name, x * hours, Double::sum);
        }
        return got;
    }

    Person person(String name) {
        for (Person p : people) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    Group group(String name) {
        for (Group f : groups) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** The group, made if new. */
    Group groupOrNew(String name) {
        Group f = group(name);
        if (f == null) { f = new Group(); f.name = name; groups.add(f); }
        return f;
    }

    int members(String group) {
        int n = 0;
        for (Person p : people) if (p.group.equalsIgnoreCase(group)) n++;
        return n;
    }

    /**
     * Sets one field of a person from text (/pop set). Returns null when done, else what was wrong.
     * Fields: hp, hpmax, state, species, group, armor, weapon1, weapon2, activity, traits (comma list), born.
     */
    String set(Person p, String field, String value) {
        try {
            switch (field.toLowerCase(Locale.ROOT)) {
                case "hp" -> p.hp = Integer.parseInt(value);
                case "hpmax" -> p.hpMax = Integer.parseInt(value);
                case "born" -> p.born = Integer.parseInt(value);
                case "state" -> p.state = value;
                case "species" -> p.species = value;
                case "group" -> { p.group = value; groupOrNew(value); }
                case "armor", "armour" -> p.armor = value;
                case "weapon1" -> p.weapon1 = value;
                case "weapon2" -> p.weapon2 = value;
                case "activity" -> p.activity = value;
                case "traits" -> { p.traits.clear(); for (String t : value.split(",")) if (!t.isBlank()) p.traits.add(t.trim()); }
                default -> { return "no field " + field + " (hp, hpmax, state, species, group, armor, weapon1, weapon2, activity, traits, born)"; }
            }
        } catch (NumberFormatException e) {
            return field + " takes a whole number, not " + value;
        }
        return null;
    }

    /** The workbook's own two rows and its two groups, as the first file holds them. */
    static Population example() {
        Population pop = new Population();
        Person a = new Person();
        a.name = "Honza"; a.hp = 25; a.hpMax = 25; a.state = "Poisoned"; a.species = "Human"; a.group = "Smugler";
        a.armor = "Death Tusk Armor"; a.weapon1 = "Death Tusk Spear II"; a.weapon2 = "AK 47"; a.activity = "Idling";
        a.traits.add("Skilled"); a.born = 2048;
        Person b = new Person();
        b.name = "Faiden"; b.hp = 15; b.hpMax = 50; b.state = "Tired"; b.species = "Human"; b.group = "Smugler";
        b.armor = "Leather Scraps"; b.weapon1 = "Knife"; b.weapon2 = "Hand Nuke"; b.activity = "Healing";
        b.traits.add("Genius"); b.born = 2042;
        pop.people.add(a);
        pop.people.add(b);
        pop.groupOrNew("Smugler").xp = 2000;
        pop.groupOrNew("kkti");
        return pop;
    }

    // ------------------------------------------------------------------ the file
    static Population load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        Population p = Store.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Population.class);
        return p != null ? p : new Population();
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Store.GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
