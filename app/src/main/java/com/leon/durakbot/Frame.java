package com.leon.durakbot;

/** Один кадр экрана (RGBA), читается по координатам. */
final class Frame {
    byte[] data;
    int w, h, rowStride, pixelStride;
    long seq;

    /** Цвет пикселя как 0xRRGGBB. */
    int get(int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= w) x = w - 1;
        if (y >= h) y = h - 1;
        int i = y * rowStride + x * pixelStride;
        if (i + 2 >= data.length) return 0;
        return ((data[i] & 255) << 16) | ((data[i + 1] & 255) << 8) | (data[i + 2] & 255);
    }

    int get(float x, float y) {
        return get(Math.round(x), Math.round(y));
    }
}
