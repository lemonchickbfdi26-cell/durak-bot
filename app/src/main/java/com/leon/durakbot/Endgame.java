package com.leon.durakbot;

import java.util.HashMap;

/**
 * Точный расчёт конца игры, когда колода пуста и карты соперника известны.
 * Игрок 0 — бот, 1 — соперник. Оценка: +1 бот выиграл, 0 ничья, -1 проиграл.
 */
final class Endgame {

    static final int MAX_NODES = 250000;

    final int trump;
    long nodes;
    boolean overflow;
    final HashMap<String, Integer> memo = new HashMap<>();

    // лучший ход в корне
    int bestType = -1;   // Durak.ATTACK / DEFEND / TRANSFER / BUTTON
    int bestCard = -1, bestTarget = -1;

    Endgame(int trump) {
        this.trump = trump;
    }

    static int rank(int id) { return id % 9; }
    static int suit(int id) { return id / 9; }

    boolean beats(int d, int a) {
        if (suit(d) == suit(a)) return rank(d) > rank(a);
        return suit(d) == trump && suit(a) != trump;
    }

    /** Таблица: атаки A[0..n-1], защиты D[i] (-1 если не побита). */
    static final class T {
        int[] A = new int[12], D = new int[12];
        int n;

        T copy() {
            T t = new T();
            System.arraycopy(A, 0, t.A, 0, 12);
            System.arraycopy(D, 0, t.D, 0, 12);
            t.n = n;
            return t;
        }

        void add(int c) { A[n] = c; D[n] = -1; n++; }

        int firstUnbeaten() {
            for (int i = 0; i < n; i++) if (D[i] < 0) return i;
            return -1;
        }

        boolean anyDefense() {
            for (int i = 0; i < n; i++) if (D[i] >= 0) return true;
            return false;
        }

        boolean rankOn(int r) {
            for (int i = 0; i < n; i++) {
                if (rank(A[i]) == r) return true;
                if (D[i] >= 0 && rank(D[i]) == r) return true;
            }
            return false;
        }

        long all() {
            long m = 0;
            for (int i = 0; i < n; i++) {
                m |= 1L << A[i];
                if (D[i] >= 0) m |= 1L << D[i];
            }
            return m;
        }

        String key() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(A[i]).append(':').append(D[i]).append(',');
            return sb.toString();
        }
    }

    int depth;

    private boolean budget() {
        if (++nodes > MAX_NODES || depth > 70) { overflow = true; return false; }
        return true;
    }

    private static int better(int p, int a, int b) { return p == 0 ? Math.max(a, b) : Math.min(a, b); }

    private void setRoot(boolean root, int type, int card, int target) {
        if (root) { bestType = type; bestCard = card; bestTarget = target; }
    }

    // ---------- начало раунда: атакующий кладёт первую карту ----------
    private int roundStart0(long[] h, int att, boolean root) {
        int def = 1 - att;
        if (h[0] == 0 && h[1] == 0) return 0;
        if (h[0] == 0) return 1;
        if (h[1] == 0) return -1;
        if (!budget()) return 0;
        String key = "R" + h[0] + "," + h[1] + "," + att;
        Integer m = root ? null : memo.get(key);
        if (m != null) return m;
        int best = att == 0 ? -2 : 2;
        int limit = Math.min(6, Long.bitCount(h[def]));
        for (int c = 0; c < 36; c++) {
            if ((h[att] & (1L << c)) == 0) continue;
            long[] nh = {h[0], h[1]};
            nh[att] &= ~(1L << c);
            T t = new T();
            t.add(c);
            int v = defenderTurnW(nh, att, t, limit, false);
            if ((att == 0 && v > best) || (att == 1 && v < best)) {
                best = v;
                setRoot(root, Durak.ATTACK, c, -1);
            }
            if ((att == 0 && best == 1) || (att == 1 && best == -1)) break;
        }
        if (!overflow) memo.put(key, best);
        return best;
    }

    // ---------- защита ----------
    private int defenderTurn0(long[] h, int att, T t, int limit, boolean root) {
        int def = 1 - att;
        if (!budget()) return 0;
        String key = "D" + h[0] + "," + h[1] + "," + att + "," + limit + "," + t.key();
        Integer m = root ? null : memo.get(key);
        if (m != null) return m;
        int best = def == 0 ? -2 : 2;
        int i = t.firstUnbeaten();
        // перевод
        if (!t.anyDefense()) {
            int r = rank(t.A[0]);
            if (t.n + 1 <= Long.bitCount(h[att])) {
                for (int c = 0; c < 36; c++) {
                    if ((h[def] & (1L << c)) == 0 || rank(c) != r) continue;
                    long[] nh = {h[0], h[1]};
                    nh[def] &= ~(1L << c);
                    T nt = t.copy();
                    nt.add(c);
                    int nlimit = Math.min(6, Long.bitCount(nh[att]));
                    int v = defenderTurnW(nh, def, nt, nlimit, false); // роли поменялись
                    if ((def == 0 && v > best) || (def == 1 && v < best)) {
                        best = v;
                        setRoot(root, Durak.TRANSFER, c, -1);
                    }
                }
            }
        }
        // отбиться первой непобитой картой
        for (int c = 0; c < 36 && !((def == 0 && best == 1) || (def == 1 && best == -1)); c++) {
            if ((h[def] & (1L << c)) == 0 || !beats(c, t.A[i])) continue;
            long[] nh = {h[0], h[1]};
            nh[def] &= ~(1L << c);
            T nt = t.copy();
            nt.D[i] = c;
            int v;
            if (nt.firstUnbeaten() >= 0) v = defenderTurnW(nh, att, nt, limit, false);
            else if (nh[def] == 0) v = roundStartW(nh, def, false); // отбился последней картой — бито
            else v = attackerThrowW(nh, att, nt, limit, false);
            if ((def == 0 && v > best) || (def == 1 && v < best)) {
                best = v;
                setRoot(root, Durak.DEFEND, c, t.A[i]);
            }
        }
        // беру
        if (!((def == 0 && best == 1) || (def == 1 && best == -1))) {
            int v = attackerGiveW(h, att, t, limit, false);
            if ((def == 0 && v > best) || (def == 1 && v < best)) {
                best = v;
                setRoot(root, Durak.BUTTON, -1, -1);
            }
        }
        if (!overflow) memo.put(key, best);
        return best;
    }

    // ---------- всё побито: атакующий подкидывает или «бито» ----------
    private int attackerThrow0(long[] h, int att, T t, int limit, boolean root) {
        int def = 1 - att;
        if (!budget()) return 0;
        String key = "A" + h[0] + "," + h[1] + "," + att + "," + limit + "," + t.key();
        Integer m = root ? null : memo.get(key);
        if (m != null) return m;
        // бито
        int best = roundStartW(h, def, false);
        setRoot(root, Durak.BUTTON, -1, -1);
        if (t.n < limit && h[def] != 0) {
            for (int c = 0; c < 36; c++) {
                if ((att == 0 && best == 1) || (att == 1 && best == -1)) break;
                if ((h[att] & (1L << c)) == 0 || !t.rankOn(rank(c))) continue;
                long[] nh = {h[0], h[1]};
                nh[att] &= ~(1L << c);
                T nt = t.copy();
                nt.add(c);
                int v = defenderTurnW(nh, att, nt, limit, false);
                if ((att == 0 && v > best) || (att == 1 && v < best)) {
                    best = v;
                    setRoot(root, Durak.ATTACK, c, -1);
                }
            }
        }
        if (!overflow) memo.put(key, best);
        return best;
    }

    // ---------- защитник берёт: атакующий подкидывает «в догонку» или пас ----------
    private int attackerGive0(long[] h, int att, T t, int limit, boolean root) {
        int def = 1 - att;
        if (!budget()) return 0;
        String key = "G" + h[0] + "," + h[1] + "," + att + "," + limit + "," + t.key();
        Integer m = root ? null : memo.get(key);
        if (m != null) return m;
        long[] taken = {h[0], h[1]};
        taken[def] |= t.all();
        int best = roundStartW(taken, att, false); // пас: забирает, атакующий ходит снова
        setRoot(root, Durak.BUTTON, -1, -1);
        if (t.n < limit) {
            for (int c = 0; c < 36; c++) {
                if ((att == 0 && best == 1) || (att == 1 && best == -1)) break;
                if ((h[att] & (1L << c)) == 0 || !t.rankOn(rank(c))) continue;
                long[] nh = {h[0], h[1]};
                nh[att] &= ~(1L << c);
                T nt = t.copy();
                nt.add(c);
                int v = attackerGiveW(nh, att, nt, limit, false);
                if ((att == 0 && v > best) || (att == 1 && v < best)) {
                    best = v;
                    setRoot(root, Durak.ATTACK, c, -1);
                }
            }
        }
        if (!overflow) memo.put(key, best);
        return best;
    }

    int roundStart(long[] h, int att, boolean root) { depth++; try { return roundStart0(h, att, root); } finally { depth--; } }
    int defenderTurn(long[] h, int att, T t, int limit, boolean root) { depth++; try { return defenderTurn0(h, att, t, limit, root); } finally { depth--; } }
    int attackerThrow(long[] h, int att, T t, int limit, boolean root) { depth++; try { return attackerThrow0(h, att, t, limit, root); } finally { depth--; } }
    int attackerGive(long[] h, int att, T t, int limit, boolean root) { depth++; try { return attackerGive0(h, att, t, limit, root); } finally { depth--; } }
    private int roundStartW(long[] h, int att, boolean root) { return roundStart(h, att, root); }
    private int defenderTurnW(long[] h, int att, T t, int limit, boolean root) { return defenderTurn(h, att, t, limit, root); }
    private int attackerThrowW(long[] h, int att, T t, int limit, boolean root) { return attackerThrow(h, att, t, limit, root); }
    private int attackerGiveW(long[] h, int att, T t, int limit, boolean root) { return attackerGive(h, att, t, limit, root); }
}
