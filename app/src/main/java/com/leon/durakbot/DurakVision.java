package com.leon.durakbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Распознавание экрана «Дурак Онлайн»: рука, стол, козырь, кнопка, чей ход. */
final class DurakVision {

    // ---------- образцы ----------
    private static final String[] RANK_NAMES = {"6", "7", "8", "9", "T", "V", "D", "K", "1"};
    private static final int[] RANK_VALUE = {0, 1, 2, 3, 8, 5, 6, 7, 4}; // T=туз, V=валет, D=дама, K=король, 1=десятка
    private static final int[] HAND_ANGLES = {-9, -6, -3, 0, 3, 6, 9};
    private static Mask[][] rankRot;   // [достоинство][угол]
    private static Mask[][] suitRot;   // [масть ♠♣♥♦][угол] — для руки/стола
    private static Mask[][] suitAll;   // [масть][36 углов] — для повёрнутого козыря
    private static Mask[] buttons;     // ready, take, bito, yourturn

    static synchronized void init() {
        if (rankRot != null) return;
        rankRot = new Mask[RANK_NAMES.length][];
        for (int i = 0; i < RANK_NAMES.length; i++) {
            Mask base = Mask.decode(Templates.RANKS[i]);
            rankRot[i] = new Mask[HAND_ANGLES.length];
            for (int a = 0; a < HAND_ANGLES.length; a++) rankRot[i][a] = base.rotate(HAND_ANGLES[a]);
        }
        suitRot = new Mask[4][];
        suitAll = new Mask[4][];
        for (int s = 0; s < 4; s++) {
            Mask base = Mask.decode(Templates.SUITS[s]); // порядок S, C, H, D = ♠♣♥♦
            suitRot[s] = new Mask[HAND_ANGLES.length];
            for (int a = 0; a < HAND_ANGLES.length; a++) suitRot[s][a] = base.rotate(HAND_ANGLES[a]);
            suitAll[s] = new Mask[36];
            for (int a = 0; a < 36; a++) suitAll[s][a] = base.rotate(a * 10 - 180);
        }
        buttons = new Mask[Templates.BUTTONS.length];
        for (int i = 0; i < buttons.length; i++) buttons[i] = Mask.decode(Templates.BUTTONS[i]);
    }

    // ---------- цвета ----------
    static int r(int c) { return (c >> 16) & 255; }
    static int g(int c) { return (c >> 8) & 255; }
    static int b(int c) { return c & 255; }

    static boolean isRed(int p) {
        int r = r(p), g = g(p), b = b(p);
        return r > 150 && r - g > 60 && r - b > 50;
    }

    static boolean isBlack(int p, int thr) {
        int r = r(p), g = g(p), b = b(p);
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx < thr && mx - mn < 50;
    }

    // ---------- компоненты ----------
    static final class Comp {
        int x, y, w, h, count;
        boolean red;
        Mask m;
        boolean ten;
    }

    /** Связные области красного или чёрного цвета (8-связность) в прямоугольнике. */
    static List<Comp> comps(Frame f, int x0, int y0, int x1, int y1, int kthr, int minCount) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(f.w, x1);
        y1 = Math.min(f.h, y1);
        int W = x1 - x0, H = y1 - y0;
        List<Comp> out = new ArrayList<>();
        if (W <= 0 || H <= 0) return out;
        byte[] col = new byte[W * H]; // 1 красный, 2 чёрный
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = f.get(x + x0, y + y0);
                if (isRed(p)) col[y * W + x] = 1;
                else if (isBlack(p, kthr)) col[y * W + x] = 2;
            }
        int[] lab = new int[W * H];
        int[] stack = new int[W * H];
        int cur = 0;
        for (int s = 0; s < W * H; s++) {
            if (col[s] == 0 || lab[s] != 0) continue;
            cur++;
            byte c = col[s];
            int sp = 0, n = 0, minX = W, minY = H, maxX = -1, maxY = -1;
            stack[sp++] = s;
            lab[s] = cur;
            while (sp > 0) {
                int i = stack[--sp];
                n++;
                int x = i % W, y = i / W;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= H) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= W) continue;
                        int j = yy * W + xx;
                        if (col[j] == c && lab[j] == 0) {
                            lab[j] = cur;
                            stack[sp++] = j;
                        }
                    }
                }
            }
            if (n < minCount) continue;
            Comp cp = new Comp();
            cp.x = minX + x0;
            cp.y = minY + y0;
            cp.w = maxX - minX + 1;
            cp.h = maxY - minY + 1;
            cp.count = n;
            cp.red = c == 1;
            cp.m = new Mask(cp.w, cp.h);
            for (int y = 0; y < cp.h; y++)
                for (int x = 0; x < cp.w; x++)
                    if (lab[(y + minY) * W + x + minX] == cur) cp.m.v[y * cp.w + x] = 1f;
            out.add(cp);
        }
        return out;
    }

    // ---------- сравнение с образцом ----------

    /** Похожесть значка на образец (0..1), значок может быть обрезан справа соседней картой. */
    static float iou(Mask comp, Mask t) {
        int h = comp.h, w = comp.w;
        int sw = Math.max(2, Math.round(t.w * h / (float) t.h));
        if (sw < w && w > sw * 1.25f) return -1;
        Mask tt = t.resize(sw, h);
        float inter = 0, uni = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float a = comp.v[y * w + x];
                float c = x < sw ? tt.v[y * sw + x] : 0f;
                inter += Math.min(a, c);
                uni += Math.max(a, c);
            }
        return uni > 0 ? inter / uni : 0;
    }

    static float best(Mask comp, Mask[] rots) {
        float b = -1;
        for (Mask t : rots) b = Math.max(b, iou(comp, t));
        return b;
    }

    /** Достоинство 0..8 или -1. */
    static int classifyRank(Comp g, float[] outScore) {
        if (g.ten) {
            outScore[0] = 1f;
            return 4;
        }
        int holes = Math.min(g.m.holes(), 2);
        int[][] groups = {{1, 4, 7, 8}, {0, 3, 6}, {2, 5}}; // индексы в RANK_NAMES: 0 дыр: 7,T,K,1; 1: 6,9,D; 2: 8,V
        int bestI = -1;
        float bestS = -1;
        for (int i : groups[holes]) {
            float s = best(g.m, rankRot[i]);
            if (s > bestS) { bestS = s; bestI = i; }
        }
        if (bestS < 0.45f) {
            for (int i = 0; i < RANK_NAMES.length; i++) {
                float s = best(g.m, rankRot[i]);
                if (s > bestS) { bestS = s; bestI = i; }
            }
        }
        if (bestI < 0) return -1;
        // «Т» и «1» (десятка): если похожесть почти равна — смотрим на перекладину сверху
        if ((bestI == 4 || bestI == 8)
                && Math.abs(best(g.m, rankRot[4]) - best(g.m, rankRot[8])) < 0.05f) {
            int rows = Math.max(2, (int) (g.m.h * 0.1f));
            float sum = 0;
            for (int y = 0; y < rows; y++) for (int x = 0; x < g.m.w; x++) sum += g.m.v[y * g.m.w + x];
            bestI = sum / (rows * g.m.w) > 0.6f ? 4 : 8;
        }
        outScore[0] = bestS;
        return RANK_VALUE[bestI];
    }

    /** Масть 0..3 (♠♣♥♦). */
    static int classifySuit(Comp s, Mask[][] set, float[] outScore) {
        int[] cand = s.red ? new int[]{2, 3} : new int[]{0, 1};
        int bi = -1;
        float bs = -1;
        for (int i : cand) {
            float v = best(s.m, set[i]);
            if (v > bs) { bs = v; bi = i; }
        }
        outScore[0] = bs;
        return bi;
    }

    // ---------- поиск карт (значок достоинства + масть под ним) ----------

    static List<Card> findCards(Frame f, int x0, int y0, int x1, int y1, int hmin, int hmax, int kthr) {
        List<Comp> cs = comps(f, x0, y0, x1, y1, kthr, 25);
        List<Comp> cs2 = kthr == 140 ? cs : comps(f, x0, y0, x1, y1, 140, 25);
        List<Comp> glyphs = new ArrayList<>();
        for (Comp c : cs) {
            float ar = c.w / (float) c.h;
            if (c.h >= hmin && c.h <= hmax && ar >= 0.1f && ar <= 0.75f) glyphs.add(c);
        }
        Collections.sort(glyphs, (a, b) -> a.x - b.x);
        // склеиваем «1» и «0» в десятку
        List<Comp> merged = new ArrayList<>();
        boolean[] used = new boolean[glyphs.size()];
        for (int i = 0; i < glyphs.size(); i++) {
            if (used[i]) continue;
            Comp gl = glyphs.get(i);
            for (int j = i + 1; j < glyphs.size(); j++) {
                Comp o = glyphs.get(j);
                if (used[j] || o.red != gl.red) continue;
                int gap = o.x - (gl.x + gl.w);
                if (gap >= -3 && gap <= Math.max(6, 0.07f * gl.h) && Math.abs(o.y - gl.y) < 0.1f * gl.h
                        && Math.abs(o.h - gl.h) < 0.12f * gl.h && gl.w < 0.34f * gl.h && o.w > gl.w * 1.05f) {
                    Comp t = new Comp();
                    t.x = gl.x; t.y = gl.y; t.h = gl.h; t.w = o.x + o.w - gl.x; t.red = gl.red; t.m = gl.m; t.ten = true;
                    gl = t;
                    used[j] = true;
                    break;
                }
            }
            merged.add(gl);
        }
        List<Card> out = new ArrayList<>();
        float[] sc = new float[1];
        Comp lastSuit = null;
        Comp lastGlyph = null;
        for (Comp gl : merged) {
            float H = gl.h;
            Comp bestS = null;
            float bestDy = 1e9f;
            for (Comp s : cs2) {
                if (s.red != gl.red) continue;
                if (s.h < 0.3f * H || s.h > 0.75f * H || s.w < 0.3f * H || s.w > 0.75f * H) continue;
                float dy = s.y - (gl.y + gl.h), dx = s.x - gl.x;
                if (dy >= -0.08f * H && dy <= 0.35f * H && dx >= -0.33f * H && dx <= 0.18f * H && dy < bestDy) {
                    bestDy = dy;
                    bestS = s;
                }
            }
            if (bestS == null) continue;
            // одна масть — одна карта: второй значок над той же мастью — это «0» от десятки
            if (bestS == lastSuit && lastGlyph != null && !out.isEmpty()) {
                if (!lastGlyph.ten && lastGlyph.w < 0.36f * lastGlyph.h) {
                    Card prev = out.get(out.size() - 1);
                    Card ten = new Card(4, prev.suit);
                    ten.x = prev.x; ten.y = prev.y; ten.score = prev.score;
                    out.set(out.size() - 1, ten);
                    lastGlyph.ten = true;
                }
                continue;
            }
            lastSuit = bestS;
            lastGlyph = gl;
            int rank = classifyRank(gl, sc);
            float rs = sc[0];
            int suit = classifySuit(bestS, suitRot, sc);
            float ss = sc[0];
            if (rank < 0 || suit < 0 || rs < 0.35f || ss < 0.4f) { lastSuit = null; lastGlyph = null; continue; }
            Card c = new Card(rank, suit);
            c.x = gl.x + gl.w / 2f;
            c.y = gl.y + gl.h / 2f;
            c.score = Math.min(rs, ss);
            out.add(c);
        }
        return out;
    }

    // ---------- весь экран ----------

    static final int BTN_NONE = 0, BTN_READY = 1, BTN_TAKE = 2, BTN_BITO = 3, BTN_YOURTURN = 4, BTN_OTHER = 5;
    static final int ROLE_UNKNOWN = 0, ROLE_ATTACK = 1, ROLE_DEFEND = 2;

    static final class State {
        List<Card> hand = new ArrayList<>();
        List<Card> table = new ArrayList<>();
        int trump = -1;
        boolean deckEmpty;
        int button = BTN_NONE;
        int role = ROLE_UNKNOWN;
        boolean trumpSeen;     // козырь виден на этом кадре
        boolean oppTakes;      // у соперника жёлтое облачко «Беру»

        int unbeaten() {
            int n = 0;
            for (Card c : table) if (!c.isDefense && !c.covered) n++;
            return n;
        }

        String key() {
            StringBuilder sb = new StringBuilder();
            for (Card c : hand) sb.append(c).append(',');
            sb.append('|');
            for (Card c : table) sb.append(c).append(c.covered ? "*" : "").append(',');
            sb.append('|').append(button).append(role).append(trump).append(deckEmpty).append(oppTakes);
            return sb.toString();
        }
    }

    static int X(Frame f, float bx) { return Math.round(bx * f.w / 720f); }
    static int Y(Frame f, float by) { return Math.round(by * f.h / 1600f); }
    static int S(Frame f, float v) { return Math.round(v * f.h / 1600f); }

    static State read(Frame f) {
        init();
        State st = new State();
        st.hand = findCards(f, 0, Y(f, 1090), f.w, Y(f, 1352), S(f, 85), S(f, 125), 95);
        List<Card> tb = findCards(f, X(f, 100), Y(f, 430), X(f, 700), Y(f, 1000), S(f, 40), S(f, 65), 140);
        // пары «атака — защита»: защитная карта лежит правее и ниже атакующей
        Collections.sort(tb, (a, b) -> Float.compare(a.x, b.x));
        for (Card d : tb) {
            for (Card a : tb) {
                if (a == d || a.isDefense || a.covered) continue;
                float dx = d.x - a.x, dy = d.y - a.y;
                if (dx >= S(f, 30) && dx <= S(f, 80) && dy >= S(f, 4) && dy <= S(f, 32)) {
                    d.isDefense = true;
                    a.covered = true;
                    a.coveredBy = d;
                    break;
                }
            }
        }
        st.table = tb;
        readTrump(f, st);
        st.button = readButton(f);
        st.role = readRole(f);
        st.oppTakes = yellow(f, 250, 90, 470, 185) > S(f, 800);
        return st;
    }

    static void readTrump(Frame f, State st) {
        List<Comp> cs = comps(f, 0, Y(f, 570), X(f, 120), Y(f, 692), 120, 60);
        float bestS = -1;
        int bestSuit = -1;
        boolean big = false;
        float[] sc = new float[1];
        for (Comp c : cs) {
            float ar = c.w / (float) c.h;
            if (c.h < S(f, 14) || c.h > S(f, 110) || c.w < S(f, 14) || c.w > S(f, 110) || ar < 0.6f || ar > 1.6f) continue;
            if (c.y <= Y(f, 570) || c.y + c.h >= Y(f, 691)) continue;
            boolean isBig = c.w >= S(f, 60);
            if (!isBig && c.x + c.w > X(f, 75)) continue; // мелкий значок — только у повёрнутого козыря слева
            int s = classifySuit(c, suitAll, sc);
            if (s >= 0 && sc[0] > bestS) {
                bestS = sc[0];
                bestSuit = s;
                big = isBig;
            }
        }
        if (bestS >= 0.45f) {
            st.trump = bestSuit;
            st.deckEmpty = big;
            st.trumpSeen = true;
        }
    }

    static int readButton(Frame f) {
        int x0 = X(f, 5), x1 = X(f, 290), y0 = Y(f, 1378), y1 = Y(f, 1495);
        int minX = 99999, minY = 99999, maxX = -1, maxY = -1, cnt = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (isRed(f.get(x, y))) {
                    cnt++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (cnt < S(f, 600) * (f.w / 720f)) return BTN_NONE;
        Mask m = new Mask(maxX - minX + 1, maxY - minY + 1);
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++)
                if (isRed(f.get(x, y))) m.v[(y - minY) * m.w + x - minX] = 1f;
        int bi = -1;
        float bs = 0;
        for (int i = 0; i < buttons.length; i++) {
            Mask t = buttons[i];
            float ratio = (m.w / (float) m.h) / (t.w / (float) t.h);
            if (ratio < 0.7f || ratio > 1.4f) continue;
            Mask tt = t.resize(m.w, m.h);
            float inter = 0, uni = 0;
            for (int k = 0; k < m.v.length; k++) {
                inter += Math.min(m.v[k], tt.v[k]);
                uni += Math.max(m.v[k], tt.v[k]);
            }
            float s = uni > 0 ? inter / uni : 0;
            if (s > bs) { bs = s; bi = i; }
        }
        if (bi < 0 || bs < 0.5f) return BTN_OTHER;
        return bi + 1;
    }

    static int yellow(Frame f, int bx0, int by0, int bx1, int by1) {
        int n = 0;
        for (int y = Y(f, by0); y < Y(f, by1); y++)
            for (int x = X(f, bx0); x < X(f, bx1); x++) {
                int p = f.get(x, y);
                if (r(p) > 230 && g(p) > 200 && b(p) < 90) n++;
            }
        return n;
    }

    /** Атакую я или отбиваюсь — по цвету рамки моей аватарки (красная — хожу, зелёная — отбиваюсь). */
    static int readRole(Frame f) {
        int red = 0, green = 0;
        for (int y = Y(f, 1370); y < Y(f, 1505); y += 2)
            for (int x = X(f, 292); x < X(f, 428); x += 2) {
                int p = f.get(x, y);
                int r = r(p), g = g(p), b = b(p);
                if (r > 220 && g < 110 && b < 120) red++;
                else if (g > 180 && r > 110 && r < 185 && b < 80) green++;
            }
        if (green > 400) return ROLE_DEFEND;
        if (red > 120) return ROLE_ATTACK;
        return ROLE_UNKNOWN;
    }
}
