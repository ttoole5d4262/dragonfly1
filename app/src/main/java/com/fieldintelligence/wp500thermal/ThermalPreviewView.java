package com.fieldintelligence.wp500thermal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

public class ThermalPreviewView extends View {
    public enum Palette { IRON, GRAY }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private int[] pixels;
    private Palette palette = Palette.IRON;
    private static final int W = 256, H = 192;

    public ThermalPreviewView(Context c) { super(c); setBackgroundColor(0xff000000); }
    public void togglePalette() { palette = palette == Palette.IRON ? Palette.GRAY : Palette.IRON; }
    public Palette getPalette() { return palette; }

    public synchronized void updateYuyv(byte[] frame) {
        int need = W * H * 2;
        if (frame == null || frame.length < need) return;
        if (bitmap == null) bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        if (pixels == null) pixels = new int[W * H];
        int p = 0;
        for (int i = 0; i + 3 < need && p + 1 < pixels.length; i += 4) {
            int y0 = frame[i] & 0xff;
            int y1 = frame[i + 2] & 0xff;
            pixels[p++] = color(y0);
            pixels[p++] = color(y1);
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        postInvalidate();
    }

    private int color(int y) {
        if (palette == Palette.GRAY) return 0xff000000 | (y << 16) | (y << 8) | y;
        float t = y / 255f;
        int r, g, b;
        if (t < .25f) { r = (int)(t * 4 * 80); g = 0; b = (int)(40 + t * 4 * 100); }
        else if (t < .50f) { float q=(t-.25f)*4; r=(int)(80+175*q); g=(int)(30*q); b=(int)(140*(1-q)); }
        else if (t < .75f) { float q=(t-.50f)*4; r=255; g=(int)(30+190*q); b=0; }
        else { float q=(t-.75f)*4; r=255; g=(int)(220+35*q); b=(int)(220*q); }
        return 0xff000000 | (clamp(r)<<16) | (clamp(g)<<8) | clamp(b);
    }
    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    @Override protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null) {
            paint.setColor(0xff888888); paint.setTextSize(36f); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("THERMAL", getWidth()/2f, getHeight()/2f, paint);
            return;
        }
        Rect dst = fit(bitmap.getWidth(), bitmap.getHeight(), getWidth(), getHeight());
        c.drawBitmap(bitmap, null, dst, paint);
        paint.setColor(0xffffffff); paint.setStrokeWidth(2f);
        float cx=getWidth()/2f, cy=getHeight()/2f;
        c.drawLine(cx-14,cy,cx+14,cy,paint); c.drawLine(cx,cy-14,cx,cy+14,paint);
    }

    private Rect fit(int sw,int sh,int dw,int dh) {
        float s=Math.min(dw/(float)sw, dh/(float)sh);
        int w=(int)(sw*s), h=(int)(sh*s), l=(dw-w)/2, t=(dh-h)/2;
        return new Rect(l,t,l+w,t+h);
    }
}
