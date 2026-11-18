package com.tidalgui.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons drawn on a 24-unit grid so they stay crisp at any size and match the theme colour. */
final class PlayerIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    private final int dimension;

    PlayerIcon(String name, int color, int dimension) {
        this.name = name;
        this.dimension = dimension;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (name) {
            case "home": path(canvas, 3,11,12,3,21,11,21,21,14,21,14,15,10,15,10,21,3,21,3,11); break;
            case "library":
                path(canvas, 4,4,4,20); path(canvas, 9,4,9,20);
                path(canvas, 14,4,14,20); path(canvas, 18,5,22,19); break;
            case "search":
                canvas.drawCircle(10,10,6,paint); path(canvas,14.5f,14.5f,21,21); break;
            case "sources":
                canvas.drawCircle(12,12,2,paint); canvas.drawCircle(12,12,6,paint);
                canvas.drawCircle(12,12,10,paint); break;
            case "settings":
                path(canvas,5,4,5,20); path(canvas,12,4,12,20); path(canvas,19,4,19,20);
                path(canvas,2,9,8,9); path(canvas,9,15,15,15); path(canvas,16,8,22,8); break;
            case "queue":
                path(canvas,4,6,20,6); path(canvas,4,12,20,12); path(canvas,4,18,14,18); break;
            case "down": path(canvas,5,9,12,16,19,9); break;
            case "back": path(canvas,14,5,7,12,14,19); break;
            case "forward": path(canvas,10,5,17,12,10,19); break;
            case "more":
                for (int i = 5; i <= 19; i += 7) canvas.drawCircle(i,12,1.2f,fill); break;
            case "play": filled(canvas, 7,4.5f,19.5f,12,7,19.5f); break;
            case "pause":
                canvas.drawRoundRect(new RectF(6,4.5f,10,19.5f),1.2f,1.2f,fill);
                canvas.drawRoundRect(new RectF(14,4.5f,18,19.5f),1.2f,1.2f,fill); break;
            case "previous":
                filled(canvas, 19,5,9,12,19,19); canvas.drawRoundRect(new RectF(5,5,7.5f,19),1,1,fill); break;
            case "next":
                filled(canvas, 5,5,15,12,5,19); canvas.drawRoundRect(new RectF(16.5f,5,19,19),1,1,fill); break;
            case "shuffle":
                path(canvas,3,7,7,7,15,17,21,17); path(canvas,3,17,7,17,9.5f,13.8f);
                path(canvas,12.5f,10.2f,15,7,21,7); path(canvas,18,4,21,7,18,10); path(canvas,18,14,21,17,18,20); break;
            case "repeat":
            case "repeat-one":
                path(canvas,4,11,4,8,6,6,19,6); path(canvas,16,3,19,6,16,9);
                path(canvas,20,13,20,16,18,18,5,18); path(canvas,8,15,5,18,8,21);
                if (name.equals("repeat-one")) path(canvas,11,10.5f,12.5f,9.5f,12.5f,14.5f); break;
            case "heart": heart(canvas, paint); break;
            case "heart-filled": heart(canvas, fill); break;
            case "lyrics":
                path(canvas,4,6,15,6); path(canvas,4,11,13,11); path(canvas,4,16,9,16);
                canvas.drawCircle(15.5f,17.5f,2.5f,paint); path(canvas,18,17.5f,18,8,21,9); break;
            case "info":
                canvas.drawCircle(12,12,9,paint); path(canvas,12,11,12,16.5f); canvas.drawCircle(12,7.8f,1.1f,fill); break;
            case "comment":
                path(canvas,4,5,20,5,20,16,10,16,6,20,6,16,4,16,4,5); break;
            case "download":
                path(canvas,12,4,12,15); path(canvas,7,10.5f,12,15.5f,17,10.5f); path(canvas,5,20,19,20); break;
            case "plus": path(canvas,12,5,12,19); path(canvas,5,12,19,12); break;
            case "refresh":
                canvas.drawArc(new RectF(5,5,19,19),-60,290,false,paint); path(canvas,18.5f,3.5f,18.5f,7.8f,14.2f,7.8f); break;
            case "sort":
                path(canvas,7,4,7,20); path(canvas,4,17,7,20,10,17); path(canvas,17,20,17,4); path(canvas,14,7,17,4,20,7); break;
            case "user":
                canvas.drawCircle(12,8.5f,4,paint); path(canvas,4.5f,20,5.5f,16.5f,9,14.5f,15,14.5f,18.5f,16.5f,19.5f,20); break;
            case "folder":
                path(canvas,3,6.5f,3,19,21,19,21,8.5f,11.5f,8.5f,9.5f,5.5f,4,5.5f,3,6.5f); break;
            case "note":
                canvas.drawCircle(8,17,3,paint); path(canvas,11,17,11,4.5f,19,3,19,14);
                canvas.drawCircle(16,14,3,paint); break;
            default: break;
        }
        canvas.restore();
    }

    private void heart(Canvas canvas, Paint style) {
        Path shape = new Path();
        shape.moveTo(12,20);
        shape.cubicTo(5,15.5f,2.5f,12,2.5f,8.6f);
        shape.cubicTo(2.5f,5.8f,4.7f,3.8f,7.3f,3.8f);
        shape.cubicTo(9.3f,3.8f,11,5,12,6.8f);
        shape.cubicTo(13,5,14.7f,3.8f,16.7f,3.8f);
        shape.cubicTo(19.3f,3.8f,21.5f,5.8f,21.5f,8.6f);
        shape.cubicTo(21.5f,12,19,15.5f,12,20);
        shape.close();
        canvas.drawPath(shape, style);
    }

    private void filled(Canvas canvas, float... vertices) {
        Path shape = new Path();
        shape.moveTo(vertices[0],vertices[1]);
        for (int i = 2; i < vertices.length; i += 2) shape.lineTo(vertices[i],vertices[i+1]);
        shape.close();
        canvas.drawPath(shape, fill);
        canvas.drawPath(shape, paint);
    }

    private void path(Canvas canvas, float... vertices) {
        Path shape = new Path();
        shape.moveTo(vertices[0],vertices[1]);
        for (int i = 2; i < vertices.length; i += 2) shape.lineTo(vertices[i],vertices[i+1]);
        canvas.drawPath(shape,paint);
    }

    @Override public int getIntrinsicWidth() { return dimension; }
    @Override public int getIntrinsicHeight() { return dimension; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); fill.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); fill.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
