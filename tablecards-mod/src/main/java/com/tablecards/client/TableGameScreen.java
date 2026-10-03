package com.tablecards.client;

import com.tablecards.net.ChoosePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The card game screen: the board as text on the left (scroll with the mouse wheel), the current
 * question and one button per legal move on the right. Everything is decided on the server.
 */
public class TableGameScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int BUTTON_H = 20;
    private static final int GAP = 2;
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int SECTION_COLOR = 0xFFE066;
    private static final int LINE_COLOR = 0xDDDDDD;
    private static final int LOG_COLOR = 0x9FB4C8;
    private static final int PROMPT_COLOR = 0x7CFC9A;

    private ViewModel view;
    private int page;
    private double scroll;
    private boolean sent;
    private boolean confirmConcede;

    public TableGameScreen(ViewModel view) {
        super(Text.literal(view.title));
        this.view = view;
    }

    /** A new view arrived from the server. */
    public void update(ViewModel next) {
        if (next.seq != view.seq) {
            page = 0;
            confirmConcede = false;
        }
        view = next;
        sent = false;
        clearAndInit();
    }

    private int splitX() {
        return (int) (width * 0.56);
    }

    private int buttonsTop() {
        return MARGIN + 14 + promptLines().size() * 10 + 6;
    }

    private List<OrderedText> promptLines() {
        int w = width - splitX() - MARGIN * 2;
        return textRenderer.wrapLines(Text.literal(view.prompt), Math.max(40, w));
    }

    @Override
    protected void init() {
        int x = splitX() + MARGIN;
        int w = width - x - MARGIN;
        int bottomRow = height - MARGIN - BUTTON_H;
        int top = buttonsTop();
        int perPage = Math.max(1, (bottomRow - GAP - BUTTON_H - GAP - top) / (BUTTON_H + GAP));
        int pages = Math.max(1, (view.options.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int start = page * perPage;
        for (int i = start; i < Math.min(view.options.size(), start + perPage); i++) {
            final int index = i;
            String label = view.options.get(i);
            ButtonWidget b = ButtonWidget.builder(Text.literal(label), btn -> choose(index))
                    .dimensions(x, top + (i - start) * (BUTTON_H + GAP), w, BUTTON_H)
                    .tooltip(Tooltip.of(Text.literal(label)))
                    .build();
            b.active = view.yourTurn && !sent;
            addDrawableChild(b);
        }

        int pagerY = bottomRow - BUTTON_H - GAP;
        if (pages > 1) {
            int half = (w - GAP) / 2;
            ButtonWidget prev = ButtonWidget.builder(Text.literal("< Previous"), btn -> {
                page--;
                clearAndInit();
            }).dimensions(x, pagerY, half, BUTTON_H).build();
            prev.active = page > 0;
            ButtonWidget next = ButtonWidget.builder(Text.literal("More (" + (page + 1) + "/" + pages + ") >"), btn -> {
                page++;
                clearAndInit();
            }).dimensions(x + half + GAP, pagerY, half, BUTTON_H).build();
            next.active = page < pages - 1;
            addDrawableChild(prev);
            addDrawableChild(next);
        }

        int half = (w - GAP) / 2;
        if (view.canConcede) {
            addDrawableChild(ButtonWidget.builder(Text.literal(confirmConcede ? "Really concede?" : "Concede"), btn -> {
                if (confirmConcede) {
                    send(ChoosePayload.CONCEDE);
                } else {
                    confirmConcede = true;
                    clearAndInit();
                }
            }).dimensions(x, bottomRow, half, BUTTON_H).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal(view.over ? "Close" : "Hide (right-click table to return)"),
                btn -> close()).dimensions(x + half + GAP, bottomRow, half, BUTTON_H)
                .tooltip(Tooltip.of(Text.literal("You can reopen this screen by right-clicking the table.")))
                .build());
    }

    private void choose(int index) {
        send(index);
    }

    private void send(int option) {
        if (sent) {
            return;
        }
        sent = true;
        ClientPlayNetworking.send(new ChoosePayload(view.seq, option));
        clearAndInit(); // greys out the buttons until the server answers
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int split = splitX();
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(view.title), width / 2, MARGIN - 4, TITLE_COLOR);

        // left: the board
        int left = MARGIN;
        int top = MARGIN + 10;
        int right = split - MARGIN / 2;
        int bottom = height - MARGIN;
        ctx.fill(left - 4, top - 4, right + 4, bottom + 2, 0x99000000);
        List<Line> lines = boardLines(right - left);
        int total = lines.size() * 10;
        int visible = bottom - top;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - visible)));
        ctx.enableScissor(left, top, right, bottom);
        int y = top - (int) scroll;
        for (Line l : lines) {
            if (y > top - 10 && y < bottom) {
                ctx.drawText(textRenderer, l.text, left, y, l.color, true);
            }
            y += 10;
        }
        ctx.disableScissor();
        if (total > visible) {
            ctx.drawText(textRenderer, Text.literal("scroll for more"), right - textRenderer.getWidth("scroll for more"), bottom - 9, 0x777777, false);
        }

        // right: the question
        int px = split + MARGIN;
        int py = MARGIN + 10;
        for (OrderedText t : promptLines()) {
            ctx.drawText(textRenderer, t, px, py, view.yourTurn ? PROMPT_COLOR : LINE_COLOR, true);
            py += 10;
        }
        if (sent) {
            ctx.drawText(textRenderer, Text.literal("..."), px, py, LINE_COLOR, true);
        }
    }

    private record Line(OrderedText text, int color) {
    }

    private List<Line> boardLines(int w) {
        List<Line> out = new ArrayList<>();
        for (ViewModel.Section s : view.sections) {
            out.add(new Line(Text.literal(s.title()).asOrderedText(), SECTION_COLOR));
            for (String line : s.lines()) {
                for (OrderedText t : textRenderer.wrapLines(Text.literal(line), w - 6)) {
                    out.add(new Line(t, LINE_COLOR));
                }
            }
        }
        if (!view.log.isEmpty()) {
            out.add(new Line(Text.literal("Recent events").asOrderedText(), SECTION_COLOR));
            for (String line : view.log) {
                for (OrderedText t : textRenderer.wrapLines(Text.literal(line), w - 6)) {
                    out.add(new Line(t, LOG_COLOR));
                }
            }
        }
        return out;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX < splitX()) {
            scroll -= verticalAmount * 20;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
