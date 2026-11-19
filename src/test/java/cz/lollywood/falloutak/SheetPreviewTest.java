package cz.lollywood.falloutak;

import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every page of the sample's sheet as the dialog will get it: each line one width (so the client's centring leaves it aligned),
 * and a dump of the lines as segments ({@code build/sheet-preview.json}) that `tools/render_sheet.py` draws with the client's own
 * font, to look at the layout without a game.
 */
class SheetPreviewTest {
    @Test
    void pagesAreAlignedBlocks() throws Exception {
        Character c = Character.sample("MARKZ137");
        c.survival.put(Rules.Stat.PAIN, 12.0);
        c.bleeding = 3;
        // part 3 on the sample: an implant chain, a prosthesis, a chem in its stages, a mutation (03b)
        c.fit(Data.IMPLANTS.get("Neural Link"), 4, null);
        c.fit(Data.IMPLANTS.get("Reflex Chip"), 3, null);
        c.fit(Data.IMPLANTS.get("Subdermal Plating"), 4, null);
        c.wearOnHit(Rules.Part.TORSO, 35, false);
        c.fitLimb("left_leg", "mechanical");
        c.dose(Data.CHEMS.get("Buffout"), Part3Test.fixed(0.1, 1));
        c.add(new Character.Effect("Mutation: Scales", "mutation", "", true, new int[]{0, 0, 0, 0, 0, -1, 0},
                new java.util.LinkedHashMap<>(java.util.Map.of("dt", 2.0)), 0, null, 100));
        Map<String, List<List<List<Map<String, Object>>>>> out = new LinkedHashMap<>();
        for (SheetGui.Page pg : SheetGui.Page.values()) {
            List<List<List<Map<String, Object>>>> blocks = new ArrayList<>();
            for (List<Component> block : SheetView.page(c, pg)) {
                List<List<Map<String, Object>>> lines = new ArrayList<>();
                for (Component l : block) {
                    assertEquals(436, Txt.width(l), pg + ": a line of another width");
                    List<Map<String, Object>> segs = new ArrayList<>();
                    flatten(l, null, false, segs);
                    lines.add(segs);
                }
                blocks.add(lines);
            }
            out.put(pg.name(), blocks);
        }
        Files.writeString(Path.of("build/sheet-preview.json"), new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
    }

    private static void flatten(Component c, TextColor colorAbove, boolean boldAbove, List<Map<String, Object>> segs) {
        TextColor col = c.color() != null ? c.color() : colorAbove;
        TextDecoration.State st = c.style().decoration(TextDecoration.BOLD);
        boolean bold = st == TextDecoration.State.TRUE || st == TextDecoration.State.NOT_SET && boldAbove;
        if (c instanceof TextComponent t && !t.content().isEmpty())
            segs.add(Map.of("t", t.content(), "c", col == null ? 0xFFFFFF : col.value(), "b", bold));
        for (Component ch : c.children()) flatten(ch, col, bold, segs);
    }
}
