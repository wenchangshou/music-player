package com.tidalgui.player;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Cover used when a track or album has no artwork: a muted two-tone gradient derived from the
 * name, with its first character. The same name always gets the same colours.
 */
final class FallbackArt extends Drawable {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String letter;
    private final float hue;
    private final PlayerIcon note;

    FallbackArt(String seed) {
        String name = seed == null ? "" : seed.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 5) name = name.substring(0, dot);
        letter = name.isEmpty() ? "" : new String(Character.toChars(Character.toUpperCase(name.codePointAt(0))));
        hue = name.isEmpty() ? 165 : Math.floorMod(name.hashCode() * 37, 360);
        glyph.setColor(0xd8eef6f3);
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        note = letter.isEmpty() ? new PlayerIcon("note", 0xd8eef6f3, 0) : null;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        background.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                Color.HSVToColor(new float[]{hue, 0.42f, 0.42f}),
                Color.HSVToColor(new float[]{(hue + 40) % 360, 0.50f, 0.20f}), Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b, background);
        float size = Math.min(b.width(), b.height());
        if (note != null) {
            int half = Math.round(size * 0.21f);
            note.setBounds(b.centerX() - half, b.centerY() - half, b.centerX() + half, b.centerY() + half);
            note.draw(canvas);
            return;
        }
        glyph.setTextSize(size * 0.42f);
        Paint.FontMetrics m = glyph.getFontMetrics();
        canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - (m.ascent + m.descent) / 2, glyph);
    }

    @Override public void setAlpha(int alpha) { background.setAlpha(alpha); glyph.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { background.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
