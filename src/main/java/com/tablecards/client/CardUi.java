package com.tablecards.client;

/** Lightweight card artwork shared by the deck editor and live presentation overlay. */
public final class CardUi {
    private CardUi() {}
    public static void draw(Canvas c, ViewModel.Card card, int x, int y, int w, int h, boolean artwork) {
        if (artwork && !card.image.isEmpty() && c.art(card.image, x, y, w, h, true)) return;
        c.texture("frame_" + (card.frame.isEmpty() ? "default" : card.frame), x, y, w, h);
        float scale = Math.min(1, (w - 12f) / Math.max(1, c.textWidth(card.name)));
        c.text(card.name, x + 6, y + 8, 0xFF111111, scale, false);
        c.text(card.corner, x + 6, y + 25, 0xFF111111, .7f, false);
        int yy = y + h * 3 / 5;
        for (String paragraph : card.text) {
            String line = "";
            for (String word : paragraph.split("\\s+")) {
                if (c.textWidth(line + word) * .6f > w - 12 && !line.isEmpty()) {
                    if (yy > y + h - 10) return;
                    c.text(line, x + 6, yy, 0xFF111111, .6f, false); yy += 7; line = "";
                }
                line += word + " ";
            }
            if (yy > y + h - 10) return;
            c.text(line, x + 6, yy, 0xFF111111, .6f, false); yy += 7;
        }
    }
}
