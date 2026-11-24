package cz.lollywood.falloutak;

import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Text laid out in COLUMNS inside a dialog (Marek 11-14 on the 0.2.0 sheet: *"It is aligned to centre, it would be better if the
 * important parts were aligned, like in sheet."*). A dialog's plain message centres every line by its own width, so each line here
 * is padded to the same width in pixels: then the block is centred as a whole and is left-aligned inside, and columns line up.
 * The pixel widths are the client's own (`data/fontwidths.json`, read from the 1.21.11 client jar's font: each glyph's advance as
 * the client computes it); padding is made of spaces (4 px) and bold spaces (5 px), and every line ends with U+200C (0 px), so no
 * line ends in a space the client could trim. A line never exceeds the dialog's width, so the client never re-wraps it.
 * <p>
 * The details of a row (how a value is computed, the workbook's note) go into a HOVER tooltip on the whole row (Marek 11-14:
 * *"Would it be possible to show the gray details only on hover?"*): the client's text widget in a dialog renders the hovered
 * text's tooltip (1.21.11 `AbstractStringWidget.renderWidget`: `TOOLTIP_AND_CURSOR` when hovered, read with Mojang's mappings).
 * Two tables can stand side by side as panels ({@link #sideBySide}).
 */
public final class Txt {
    /** A number as the sheet shows it: whole without decimals, else one decimal. */
    public static String f(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    private Txt() {}

    private static final Map<Integer, Integer> ADVANCE = new HashMap<>();

    static {
        try (var in = Txt.class.getResourceAsStream("/data/fontwidths.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : o.entrySet()) ADVANCE.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
            }
        } catch (Exception ignored) {}
    }

    /** The pixel width of {@code s} in the default font; bold adds 1 a glyph. Unknown glyphs count 6 (their font is not bundled). */
    public static int width(String s, boolean bold) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += java.lang.Character.charCount(cp);
            w += ADVANCE.getOrDefault(cp, 6) + (bold ? 1 : 0);
        }
        return w;
    }

    /** The pixel width of a built component (its text children, bold inherited): what the client measures to centre the line. */
    public static int width(Component c) { return width(c, false); }

    private static int width(Component c, boolean boldAbove) {
        TextDecoration.State st = c.style().decoration(TextDecoration.BOLD);
        boolean bold = st == TextDecoration.State.TRUE || st == TextDecoration.State.NOT_SET && boldAbove;
        int w = c instanceof TextComponent t ? width(t.content(), bold) : 0;
        for (Component ch : c.children()) w += width(ch, bold);
        return w;
    }

    /** {@code px} of blank: spaces of 4 and bold spaces of 5 (any width from 12 up exactly; below that the nearest smaller). */
    public static Component pad(int px) { return pad(px, null); }

    static Component pad(int px, Component hover) {
        if (px <= 0) return Component.empty();
        int best5 = 0, best4 = 0, bestW = -1;
        for (int b = 0; b <= 3; b++) {
            if (5 * b > px) break;
            int a = (px - 5 * b) / 4, w = 4 * a + 5 * b;
            if (w > bestW) { bestW = w; best4 = a; best5 = b; }
        }
        Component c = Component.text(" ".repeat(best4));
        if (best5 > 0) c = c.append(Component.text(" ".repeat(best5)).decorate(TextDecoration.BOLD));
        return hover == null ? c : c.hoverEvent(HoverEvent.showText(hover));
    }

    /** A piece of a line: its text, colour, boldness and the tooltip of its row (null = none). */
    public record Seg(String text, TextColor color, boolean bold, Component hover) {
        int width() { return Txt.width(text, bold); }
        Component component() {
            TextComponent c = Component.text(text, color);
            if (bold) c = c.decorate(TextDecoration.BOLD);
            return hover == null ? c : c.hoverEvent(HoverEvent.showText(hover));
        }
    }

    public static Seg seg(String text, TextColor color) { return new Seg(text, color, false, null); }
    public static Seg bold(String text, TextColor color) { return new Seg(text, color, true, null); }

    /** One built line: pieces with their x positions (in pixels from the block's left edge). */
    static final class Line {
        final List<Object[]> parts = new ArrayList<>();   // {Integer x, Seg seg}
        void put(int x, Seg s) { if (!s.text().isEmpty()) parts.add(new Object[]{x, s}); }
        Line shifted(int dx) { Line l = new Line(); for (Object[] p : parts) l.parts.add(new Object[]{(Integer) p[0] + dx, p[1]}); return l; }

        Component render(int total) {
            Component out = Component.empty();
            int at = 0;
            Component lastHover = null;
            parts.sort(Comparator.comparingInt(p -> (Integer) p[0]));
            for (Object[] p : parts) {
                int x = (Integer) p[0];
                Seg s = (Seg) p[1];
                if (x > at) {   // a gap inside one row keeps the row's tooltip, so the whole row answers the mouse
                    Component pd = pad(x - at, s.hover() != null && s.hover() == lastHover ? lastHover : null);
                    out = out.append(pd);
                    at += width(pd);   // short by under 4 px below 12
                }
                out = out.append(s.component());
                at += s.width();
                lastHover = s.hover();
            }
            return out.append(pad(total - at)).append(Component.text("‌"));
        }
    }

    /** Words of {@code text} into lines no wider than {@code width} (a word wider than the line is cut by glyphs). */
    static List<String> wrap(String text, int width, boolean bold) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isBlank()) continue;   // the workbook's blank lines inside a note
            StringBuilder cur = new StringBuilder();
            for (String w : para.split(" ")) {
                if (w.isEmpty()) continue;
                String tryLine = cur.isEmpty() ? w : cur + " " + w;
                if (width(tryLine, bold) <= width) { cur.setLength(0); cur.append(tryLine); continue; }
                if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
                while (width(w, bold) > width) {   // a word longer than the line
                    int n = 1;
                    while (n < w.length() && width(w.substring(0, n + 1), bold) <= width) n++;
                    out.add(w.substring(0, n));
                    w = w.substring(n);
                }
                cur.append(w);
            }
            out.add(cur.toString());
        }
        return out;
    }

    /** A tooltip: the grey details, then the workbook's note in its own colour (an empty part left out; null if both are empty). */
    public static Component tip(String how, String note) {
        List<Component> l = new ArrayList<>();
        if (how != null && !how.isBlank()) l.add(Component.text(how, SheetColors.HOW));
        if (note != null && !note.isBlank()) l.add(Component.text(note.replace("\n\n", "\n"), SheetColors.NOTE));
        return l.isEmpty() ? null : Component.join(JoinConfiguration.newlines(), l);
    }

    /**
     * A table: a title line, rows of cells (a row can carry a tooltip) and notes. Every column but the last is as wide as its widest
     * cell (a cell can be right-aligned, for numbers); the last column takes the rest of the width and wraps; a note starts at the
     * second column and wraps to the end.
     */
    public static final class Table {
        public record Cell(String text, TextColor color, boolean right, boolean bold) {}
        private final int width;
        private final List<Object> rows = new ArrayList<>();   // Row | Note | Title
        private record Row(Cell[] cells, Component hover) {}
        private record Note(String text, TextColor color) {}
        private record Title(Seg head, Seg sub) {}
        private int[] minWidth = new int[0];
        private int gap = 8;

        public Table(int width) { this.width = width; }
        public int width() { return width; }

        public Table minWidths(int... px) { minWidth = px; return this; }
        public Table gap(int px) { gap = px; return this; }

        public Table title(String head, String sub) {
            rows.add(new Title(bold(head, NamedTextColor.GOLD), seg(sub == null ? "" : sub, SheetColors.SUB)));
            return this;
        }

        public Table row(Cell... cells) { rows.add(new Row(cells, null)); return this; }
        /** A row whose details show on hover. */
        public Table row(Component hover, Cell... cells) { rows.add(new Row(cells, hover)); return this; }
        public Table note(String text) { if (text != null && !text.isBlank()) rows.add(new Note(text, SheetColors.NOTE)); return this; }
        public Table note(String text, TextColor color) { if (text != null && !text.isBlank()) rows.add(new Note(text, color)); return this; }

        public List<Component> render() {
            List<Component> out = new ArrayList<>();
            for (Line l : lines()) out.add(l.render(width));
            return out;
        }

        List<Line> lines() {
            int cols = 0;
            for (Object r : rows) if (r instanceof Row rw) cols = Math.max(cols, rw.cells().length);
            int[] w = new int[Math.max(cols, 1)];
            for (int i = 0; i < Math.min(minWidth.length, w.length); i++) w[i] = minWidth[i];
            for (Object r : rows)
                if (r instanceof Row rw)
                    for (int i = 0; i < rw.cells().length - 1 && i < cols - 1; i++) w[i] = Math.max(w[i], Txt.width(rw.cells()[i].text(), rw.cells()[i].bold()));
            int[] x = new int[w.length];
            for (int i = 1; i < w.length; i++) x[i] = x[i - 1] + w[i - 1] + gap;
            int inner = width - 12;   // the line's last 12 px stay blank: the closing pad is then always exact
            int lastX = cols > 0 ? x[cols - 1] : 0, lastW = Math.max(40, inner - lastX);
            int noteX = cols > 1 ? x[1] : 0;
            List<Line> out = new ArrayList<>();
            for (Object r : rows) {
                if (r instanceof Title t) {
                    Line l = new Line();
                    l.put(0, t.head());
                    int hx = t.head().width() + 8;
                    List<String> sub = t.sub().text().isEmpty() ? List.of() : wrap(t.sub().text(), inner - hx, false);
                    if (!sub.isEmpty()) l.put(hx, seg(sub.get(0), t.sub().color()));
                    out.add(l);
                    for (int i = 1; i < sub.size(); i++) { Line m = new Line(); m.put(hx, seg(sub.get(i), t.sub().color())); out.add(m); }
                } else if (r instanceof Note n) {
                    for (String s : wrap(n.text(), inner - noteX, false)) { Line m = new Line(); m.put(noteX, seg(s, n.color())); out.add(m); }
                } else if (r instanceof Row rw) {
                    Cell[] cs = rw.cells();
                    Component h = rw.hover();
                    Line l = new Line();
                    List<String> tail = List.of();
                    int fixed = Math.min(cs.length, cols - 1);   // a row is given every column; the last one wraps
                    for (int i = 0; i < fixed; i++) {
                        Cell c = cs[i];
                        int cx = x[i];
                        if (c.right()) cx += w[i] - Txt.width(c.text(), c.bold());
                        l.put(cx, new Seg(c.text(), c.color(), c.bold(), h));
                    }
                    if (cs.length == cols) {
                        Cell c = cs[cols - 1];
                        tail = wrap(c.text(), lastW, c.bold());
                        if (!tail.isEmpty()) l.put(lastX, new Seg(tail.get(0), c.color(), c.bold(), h));
                    }
                    out.add(l);
                    for (int i = 1; i < tail.size(); i++) {
                        Line m = new Line();
                        m.put(lastX, new Seg(tail.get(i), cs[cols - 1].color(), cs[cols - 1].bold(), h));
                        out.add(m);
                    }
                }
            }
            return out;
        }
    }

    /** Two tables as panels side by side (a sheet's two columns); the shorter one is filled with blank lines. */
    public static List<Component> sideBySide(Table left, Table right, int gap) {
        List<Line> a = left.lines(), b = right.lines();
        int total = left.width() + gap + right.width();
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            Line l = new Line();
            if (i < a.size()) l.parts.addAll(a.get(i).parts);
            if (i < b.size()) l.parts.addAll(b.get(i).shifted(left.width() + gap).parts);
            out.add(l.render(total));
        }
        return out;
    }

    public static Table.Cell c(String text, TextColor color) { return new Table.Cell(text, color, false, false); }
    public static Table.Cell r(String text, TextColor color) { return new Table.Cell(text, color, true, false); }
    public static Table.Cell b(String text, TextColor color) { return new Table.Cell(text, color, false, true); }

    /** The sheet's palette: labels white, values by meaning, explanations a light grey, the workbook's notes a grey-blue. */
    public static final class SheetColors {
        private SheetColors() {}
        public static final TextColor HOW = TextColor.color(0xB4B4B4);
        public static final TextColor NOTE = TextColor.color(0x93B3C6);
        public static final TextColor SUB = TextColor.color(0xA8A8A8);
    }
}
