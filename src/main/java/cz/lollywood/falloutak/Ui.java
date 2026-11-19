package cz.lollywood.falloutak;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every page the players see (menus, forms, the sheet) as plain data: its lines, inputs and buttons. Shown to a real client it
 * becomes a Paper dialog; in the tests it is recorded and its buttons are clicked (Marek 11-18: *"I would like to test only UX and UI
 * in game, so try to testing everything you can test outside with unit tests."*). The mock server has no dialog provider, so nothing
 * here touches Paper's dialog classes until {@link #show} sends a page to a client.
 */
final class Ui {
    private Ui() {}

    /** A button: its label, its hover text, its width (0 = by its label), what a click does with the page's answers. */
    record Btn(String label, String tip, int width, BiConsumer<DialogResponseView, Player> then) {
        /** A click with no answers (a page without inputs), or with the answers given. */
        void click(Player p) { click(p, new Answers()); }
        void click(Player p, DialogResponseView answers) { then.accept(answers, p); }
    }

    enum Kind { TEXT, RANGE, BOOL, CHOICE }

    /** An input above a page's buttons (a text box, a slider, a tick box, a list to choose from). */
    record In(Kind kind, String key, Component label, String text, int maxLength, float min, float max, float step, float initial,
              int width, boolean on, List<String> options, String format) {
        static TextB text(String key, Component label) { return new TextB(key, label); }
        static RangeB numberRange(String key, Component label, float min, float max) { return new RangeB(key, label, min, max); }
        static BoolB bool(String key, Component label) { return new BoolB(key, label); }
        static In choice(String key, String label, List<String> options) {
            return new In(Kind.CHOICE, key, Component.text(label), "", 0, 0, 0, 0, 0, 0, false, List.copyOf(options), null);
        }
    }

    /** The builders keep the shape of Paper's own (DialogInput.text(...).initial(...).maxLength(...).build()). */
    static final class TextB {
        private final String key; private final Component label; private String initial = ""; private int max = 32;
        TextB(String key, Component label) { this.key = key; this.label = label; }
        TextB initial(String s) { initial = s == null ? "" : s; return this; }
        TextB maxLength(int n) { max = n; return this; }
        In build() { return new In(Kind.TEXT, key, label, initial, max, 0, 0, 0, 0, 0, false, List.of(), null); }
    }

    static final class RangeB {
        private final String key; private final Component label; private final float min, max; private float step = 1, initial; private int width = 200; private String format;
        RangeB(String key, Component label, float min, float max) { this.key = key; this.label = label; this.min = min; this.max = max; this.initial = min; }
        RangeB step(float s) { step = s; return this; }
        RangeB initial(float v) { initial = v; return this; }
        RangeB width(int w) { width = w; return this; }
        RangeB labelFormat(String f) { format = f; return this; }
        In build() { return new In(Kind.RANGE, key, label, "", 0, min, max, step, initial, width, false, List.of(), format); }
    }

    static final class BoolB {
        private final String key; private final Component label; private boolean initial;
        BoolB(String key, Component label) { this.key = key; this.label = label; }
        BoolB initial(boolean b) { initial = b; return this; }
        In build() { return new In(Kind.BOOL, key, label, "", 0, 0, 0, 0, 0, 0, initial, List.of(), null); }
    }

    enum Type { MULTI, CONFIRM, NOTICE }

    /**
     * One page. MULTI: the buttons and an exit button (Close or Back); CONFIRM: the first button and the exit; NOTICE: the first
     * button only. A body width of 0 = the client's default.
     */
    record Page(Player player, String title, List<Component> body, int bodyWidth, List<In> inputs, Type type, List<Btn> buttons, Btn exit,
                int columns, boolean escape) {
        String text() {
            StringBuilder sb = new StringBuilder();
            for (Component c : body) sb.append(PlainTextComponentSerializer.plainText().serialize(c)).append("\n");
            return sb.toString();
        }

        List<String> labels() { List<String> l = new ArrayList<>(); for (Btn b : buttons) l.add(b.label()); return l; }

        /** The button with this label, or else the first whose label starts with it; null if none. */
        Btn button(String label) {
            for (Btn b : buttons) if (b.label().equals(label)) return b;
            for (Btn b : buttons) if (b.label().startsWith(label)) return b;
            return exit != null && exit.label().equals(label) ? exit : null;
        }

        /** The answers a player would send untouched (each input's initial value), with some changed (key, value, ...). */
        Answers answers(Object... changed) {
            Answers a = new Answers();
            for (In i : inputs) a.values.put(i.key(), switch (i.kind()) {
                case TEXT -> i.text(); case RANGE -> i.initial(); case BOOL -> i.on(); case CHOICE -> i.options().get(0);
            });
            for (int k = 0; k + 1 < changed.length; k += 2) a.values.put((String) changed[k], changed[k + 1]);
            return a;
        }

        /** A test clicks a button by its label (exact, else the start), with the inputs' answers. */
        void click(String label, Object... changed) {
            Btn b = button(label);
            if (b == null) throw new AssertionError("no button \"" + label + "\" on \"" + title + "\": " + labels());
            b.click(player, answers(changed));
        }
    }

    /** Set by the tests: pages go here instead of to a client. */
    static Consumer<Page> recorder;

    static void show(Page pg) {
        if (recorder != null) { recorder.accept(pg); return; }
        pg.player().showDialog(toDialog(pg));
    }

    // ------------------------------------------------------------------ to Paper's dialogs (a real client only)
    static ActionButton toButton(Btn b) {
        return ActionButton.create(Component.text(b.label()), Component.text(b.tip()), b.width() > 0 ? b.width() : Menus.width(b.label()),
                DialogAction.customClick((view, audience) -> { if (audience instanceof Player p) b.then().accept(view, p); },
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(30)).build()));
    }

    static DialogInput toInput(In i) {
        return switch (i.kind()) {
            case TEXT -> DialogInput.text(i.key(), i.label()).initial(i.text()).maxLength(i.maxLength()).build();
            case RANGE -> {
                var r = DialogInput.numberRange(i.key(), i.label(), i.min(), i.max()).step(i.step()).initial(i.initial()).width(i.width());
                yield (i.format() == null ? r : r.labelFormat(i.format())).build();
            }
            case BOOL -> DialogInput.bool(i.key(), i.label()).initial(i.on()).build();
            case CHOICE -> {
                List<SingleOptionDialogInput.OptionEntry> o = new ArrayList<>();
                for (int k = 0; k < i.options().size(); k++) o.add(SingleOptionDialogInput.OptionEntry.create(i.options().get(k), Component.text(i.options().get(k)), k == 0));
                yield DialogInput.singleOption(i.key(), i.label(), o).build();
            }
        };
    }

    static Dialog toDialog(Page pg) {
        List<DialogBody> body = new ArrayList<>();
        for (Component c : pg.body()) body.add(pg.bodyWidth() > 0 ? DialogBody.plainMessage(c, pg.bodyWidth()) : DialogBody.plainMessage(c));
        List<DialogInput> in = new ArrayList<>();
        for (In i : pg.inputs()) in.add(toInput(i));
        List<ActionButton> buttons = new ArrayList<>();
        for (Btn b : pg.buttons()) buttons.add(toButton(b));
        DialogType type = switch (pg.type()) {
            case MULTI -> DialogType.multiAction(buttons, pg.exit() == null ? null : toButton(pg.exit()), pg.columns());
            case CONFIRM -> DialogType.confirmation(buttons.get(0), toButton(pg.exit()));
            case NOTICE -> DialogType.notice(buttons.get(0));
        };
        return Dialog.create(b -> b.empty().base(DialogBase.builder(Component.text(pg.title())).canCloseWithEscape(pg.escape()).body(body).inputs(in).build())
                .type(type));
    }

    /** A page's answers for a test (key → text, number or tick). */
    static final class Answers implements DialogResponseView {
        final Map<String, Object> values = new HashMap<>();

        static Answers of(Object... keyValue) {
            Answers a = new Answers();
            for (int i = 0; i + 1 < keyValue.length; i += 2) a.values.put((String) keyValue[i], keyValue[i + 1]);
            return a;
        }

        @Override public BinaryTagHolder payload() { return null; }
        @Override public String getText(String key) { Object v = values.get(key); return v == null ? null : v.toString(); }
        @Override public Boolean getBoolean(String key) { Object v = values.get(key); return v instanceof Boolean b ? b : null; }
        @Override public Float getFloat(String key) { Object v = values.get(key); return v instanceof Number n ? n.floatValue() : null; }
    }
}
