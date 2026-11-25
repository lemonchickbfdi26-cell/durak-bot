package com.leon.durakbot;

/** Чёрно-белая маска значка (1 = краска). Значения float, чтобы сглаживать при масштабе. */
final class Mask {
    final int w, h;
    final float[] v;

    Mask(int w, int h) {
        this.w = w;
        this.h = h;
        this.v = new float[w * h];
    }

    float get(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return v[y * w + x];
    }

    /** Из строки шаблона: hex по строкам, каждая строка дополнена до кратного 4 битам. */
    static Mask decode(String[] t) {
        int w = Integer.parseInt(t[1]), h = Integer.parseInt(t[2]);
        Mask m = new Mask(w, h);
        int hexPerRow = (w + 3) / 4;
        String s = t[3];
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < hexPerRow; i++) {
                int nib = Character.digit(s.charAt(y * hexPerRow + i), 16);
                for (int b = 0; b < 4; b++) {
                    int x = i * 4 + b;
                    if (x < w && (nib & (8 >> b)) != 0) m.v[y * w + x] = 1f;
                }
            }
        }
        return m;
    }

    /** Поворот на угол (градусы) с обрезкой пустых краёв. */
    Mask rotate(double deg) {
        if (deg == 0) return this;
        double a = Math.toRadians(deg), cos = Math.cos(a), sin = Math.sin(a);
        int pad = (int) (Math.max(w, h) * 0.75) + 2;
        int W = w + 2 * pad, H = h + 2 * pad;
        double cx = W / 2.0, cy = H / 2.0;
        Mask out = new Mask(W, H);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = x - cx, dy = y - cy;
                // обратный поворот
                double sx = cos * dx - sin * dy + w / 2.0;
                double sy = sin * dx + cos * dy + h / 2.0;
                out.v[y * W + x] = sample(sx - 0.5, sy - 0.5);
            }
        }
        return out.trim(0.4f);
    }

    float sample(double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double a = get(x0, y0), b = get(x0 + 1, y0), c = get(x0, y0 + 1), d = get(x0 + 1, y0 + 1);
        return (float) (a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy);
    }

    Mask trim(float thr) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (v[y * w + x] > thr) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX < 0) return new Mask(1, 1);
        Mask m = new Mask(maxX - minX + 1, maxY - minY + 1);
        for (int y = 0; y < m.h; y++)
            for (int x = 0; x < m.w; x++)
                m.v[y * m.w + x] = v[(y + minY) * w + x + minX] > thr ? 1f : 0f;
        return m;
    }

    /** Масштаб до размера nw x nh (усреднение по области). */
    Mask resize(int nw, int nh) {
        Mask m = new Mask(nw, nh);
        double sx = w / (double) nw, sy = h / (double) nh;
        for (int y = 0; y < nh; y++) {
            int ya = (int) Math.floor(y * sy), yb = Math.max(ya + 1, (int) Math.ceil((y + 1) * sy));
            for (int x = 0; x < nw; x++) {
                int xa = (int) Math.floor(x * sx), xb = Math.max(xa + 1, (int) Math.ceil((x + 1) * sx));
                float s = 0;
                int n = 0;
                for (int yy = ya; yy < yb && yy < h; yy++)
                    for (int xx = xa; xx < xb && xx < w; xx++) {
                        s += v[yy * w + xx];
                        n++;
                    }
                m.v[y * nw + x] = n == 0 ? 0 : s / n;
            }
        }
        return m;
    }

    /** Сколько замкнутых «дырок» (белых областей внутри). */
    int holes() {
        int W = w + 4, H = h + 4;
        boolean[] ink = new boolean[W * H];
        int area = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (v[y * w + x] > 0.5f) {
                    ink[(y + 2) * W + x + 2] = true;
                    area++;
                }
        int[] lab = new int[W * H];
        int[] stack = new int[W * H];
        int cur = 0, holes = 0;
        for (int start = 0; start < W * H; start++) {
            if (ink[start] || lab[start] != 0) continue;
            cur++;
            int sp = 0, size = 0;
            boolean border = false;
            stack[sp++] = start;
            lab[start] = cur;
            while (sp > 0) {
                int i = stack[--sp];
                size++;
                int x = i % W, y = i / W;
                if (x == 0 || y == 0 || x == W - 1 || y == H - 1) border = true;
                int[] nb = {i - 1, i + 1, i - W, i + W};
                for (int n : nb) {
                    if (n < 0 || n >= W * H) continue;
                    if (Math.abs((n % W) - x) > 1) continue;
                    if (!ink[n] && lab[n] == 0) {
                        lab[n] = cur;
                        stack[sp++] = n;
                    }
                }
            }
            if (!border && size >= Math.max(8, area * 0.01)) holes++;
        }
        return holes;
    }
}
