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
 * Pure Java: the tests run it. How a group gains XP is part 6's open question; here it is only recorded.
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
    }

    final List<Person> people = new ArrayList<>();
    @com.google.gson.annotations.SerializedName(value = "groups", alternate = {"factions"}) final List<Group> groups = new ArrayList<>();

    /**
     * The workbook's group level (Factions!D2): (1/50) x (25 + sqrt(5) x sqrt(XP / count + 125)) = 0.5 + sqrt(0.25 + XP per member
     * / 500), i.e. the level whose threshold in Fallout 2's table, 1000 x n (n - 1) / 2 (the Production tab), the XP PER MEMBER
     * reaches. Fractional (the part above the whole level is the way to the next); 1 with no XP or no members.
     */
    static double level(double xp, int count) {
        if (count <= 0 || xp <= 0) return 1;
        return 0.5 + Math.sqrt(0.25 + xp / count / 500);
    }

    /** Fallout 2's XP for level n (the workbook's Production tab): 1000 x n (n - 1) / 2. */
    static double xpForLevel(int n) { return 1000.0 * n * (n - 1) / 2; }

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
