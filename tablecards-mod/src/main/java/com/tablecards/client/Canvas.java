package com.tablecards.client;

/**
 * What the game screen draws with. In game this wraps Minecraft's DrawContext; the same drawing
 * code can render to an image for testing. Colors are ARGB.
 */
public interface Canvas {
    void fill(int x1, int y1, int x2, int y2, int argb);

    void gradient(int x1, int y1, int x2, int y2, int topArgb, int bottomArgb);

    /** Text at {@code scale} (1 = normal GUI text, 9 pixels tall). */
    void text(String s, float x, float y, int argb, float scale, boolean shadow);

    /** Width of {@code s} at scale 1. */
    int textWidth(String s);

    /** Draws one of the mod's GUI textures (textures/gui/&lt;name&gt;.png), stretched to the box. */
    void texture(String name, int x, int y, int w, int h);

    /**
     * Draws downloaded card art if it's ready (starting the download otherwise). Returns false when
     * there's no art to draw, so the caller draws the card itself.
     */
    boolean art(String key, int x, int y, int w, int h, boolean large);

    void push();

    void pop();

    void translate(float x, float y);

    /** Rotates the following drawing by 90 degrees clockwise around the current origin. */
    void rotate90();

    void scissor(int x1, int y1, int x2, int y2);

    void noScissor();

    default void border(int x, int y, int w, int h, int argb) {
        fill(x, y, x + w, y + 1, argb);
        fill(x, y + h - 1, x + w, y + h, argb);
        fill(x, y + 1, x + 1, y + h - 1, argb);
        fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }
}
