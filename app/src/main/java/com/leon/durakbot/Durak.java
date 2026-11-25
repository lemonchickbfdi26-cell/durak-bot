package com.leon.durakbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Стратегия переводного дурака 1 на 1 (36 карт) + память вышедших карт. */
final class Durak {

    static final int WAIT = 0, ATTACK = 1, DEFEND = 2, TRANSFER = 3, BUTTON = 4;

    static final class Move {
        final int type;
        final Card card, target;
        final String why;

        Move(int type, Card card, Card target, String why) {
            this.type = type;
            this.card = card;
            this.target = target;
            this.why = why;
        }
    }

    int trump = -1;
    boolean deckEmpty;
    final boolean[] gone = new boolean[36];   // ушли в «бито»
    private List<Card> lastTable = new ArrayList<>();
    private boolean lastAllCovered;

    void newGame() {
        trump = -1;
        deckEmpty = false;
        java.util.Arrays.fill(gone, false);
        lastTable = new ArrayList<>();
        lastAllCovered = false;
    }

    int cost(Card c) {
        return c.rank + (c.suit == trump ? 9 : 0);
    }

    boolean beats(Card d, Card a) {
        if (d.suit == a.suit) return d.rank > a.rank;
        return d.suit == trump && a.suit != trump;
    }

    /** Запоминаем карты, ушедшие в «бито» (стол очистился, а все карты были побиты). */
    void observe(DurakVision.State st) {
        if (st.trump >= 0) trump = st.trump;
        if (st.deckEmpty) deckEmpty = true;
        if (st.table.isEmpty() && !lastTable.isEmpty() && lastAllCovered) {
            for (Card c : lastTable) gone[c.id()] = true;
        }
        if (!st.table.isEmpty()) {
            lastTable = new ArrayList<>(st.table);
            lastAllCovered = st.unbeaten() == 0;
        } else {
            lastTable = new ArrayList<>();
        }
    }

    /** Карты, которые могут быть у соперника (не в бито, не у меня, не на столе). */
    List<Card> unknown(DurakVision.State st) {
        boolean[] seen = gone.clone();
        for (Card c : st.hand) seen[c.id()] = true;
        for (Card c : st.table) seen[c.id()] = true;
        List<Card> out = new ArrayList<>();
        for (int s = 0; s < 4; s++)
            for (int r = 0; r < 9; r++)
                if (!seen[s * 9 + r]) out.add(new Card(r, s));
        return out;
    }

    // ================= решение =================

    Move decide(DurakVision.State st, java.util.Set<String> failed) {
        if (st.button == DurakVision.BTN_READY) return new Move(BUTTON, null, null, "Готов");
        if (st.hand.isEmpty()) return new Move(WAIT, null, null, "нет карт");
        if (trump < 0) return new Move(WAIT, null, null, "не вижу козырь");

        if (st.role == DurakVision.ROLE_DEFEND) {
            if (st.unbeaten() == 0) return new Move(WAIT, null, null, "жду ход соперника");
            return defend(st, failed);
        }
        if (st.role == DurakVision.ROLE_ATTACK) {
            if (st.table.isEmpty()) return attack(st, failed);
            boolean canAct = st.button == DurakVision.BTN_BITO || st.button == DurakVision.BTN_OTHER
                    || st.button == DurakVision.BTN_YOURTURN;
            if (!canAct) return new Move(WAIT, null, null, "соперник думает");
            return throwIn(st, failed, st.unbeaten() > 0);
        }
        return new Move(WAIT, null, null, "жду");
    }

    private boolean isFailed(java.util.Set<String> failed, Card c) {
        return failed.contains(c.toString());
    }

    private Move attack(DurakVision.State st, java.util.Set<String> failed) {
        List<Card> hand = new ArrayList<>();
        for (Card c : st.hand) if (!isFailed(failed, c)) hand.add(c);
        if (hand.isEmpty()) return new Move(WAIT, null, null, "все ходы не прошли");

        // колода кончилась — знаем карты соперника: ходим тем, что он не побьёт
        if (deckEmpty) {
            List<Card> opp = unknown(st);
            Card bestUnbeatable = null;
            for (Card c : hand) {
                boolean canBeat = false;
                for (Card o : opp) if (beats(o, c)) { canBeat = true; break; }
                if (!canBeat && (bestUnbeatable == null || cost(c) < cost(bestUnbeatable))) bestUnbeatable = c;
            }
            if (bestUnbeatable != null) return new Move(ATTACK, bestUnbeatable, null, "он это не побьёт");
        }

        // обычный ход: самая дешёвая не козырная, лучше та, у которой есть пара (потом подкинем)
        Card best = null;
        double bestScore = 1e9;
        for (Card c : hand) {
            int same = 0;
            for (Card o : hand) if (o != c && o.rank == c.rank && o.suit != trump) same++;
            double sc = cost(c) - (c.suit != trump ? same * 1.5 : 0);
            if (sc < bestScore) { bestScore = sc; best = c; }
        }
        return new Move(ATTACK, best, null, "хожу с младшей");
    }

    private Move throwIn(DurakVision.State st, java.util.Set<String> failed, boolean defenderTakes) {
        boolean[] ranks = new boolean[9];
        int attacks = 0;
        for (Card c : st.table) {
            ranks[c.rank] = true;
            if (!c.isDefense) attacks++;
        }
        Card best = null;
        if (attacks < 6) {
            for (Card c : st.hand) {
                if (!ranks[c.rank] || isFailed(failed, c)) continue;
                boolean ok;
                if (defenderTakes) {
                    // соперник берёт — отдаём ему ненужное (но не козыри и не тузы, пока есть колода)
                    ok = c.suit != trump && (deckEmpty || c.rank <= 6);
                } else {
                    ok = c.suit != trump && (deckEmpty || c.rank <= 4);
                }
                if (ok && (best == null || cost(c) < cost(best))) best = c;
            }
        }
        if (best != null) return new Move(ATTACK, best, null, defenderTakes ? "подкидываю ему" : "подкидываю");
        return new Move(BUTTON, null, null, defenderTakes ? "Пас" : "Бито");
    }

    private Move defend(DurakVision.State st, java.util.Set<String> failed) {
        List<Card> attacks = new ArrayList<>();
        boolean anyDefense = false;
        for (Card c : st.table) {
            if (c.isDefense) anyDefense = true;
            else if (!c.covered) attacks.add(c);
        }
        // план отбоя: самые дорогие атаки закрываем первыми, каждой — самой дешёвой картой
        List<Card> sorted = new ArrayList<>(attacks);
        Collections.sort(sorted, (a, b) -> cost(b) - cost(a));
        List<Card> used = new ArrayList<>();
        Card firstTarget = null, firstBeater = null;
        int planCost = 0, maxCost = 0;
        boolean usesTrumpOnPlain = false;
        boolean possible = true;
        for (Card a : sorted) {
            Card bestB = null;
            for (Card h : st.hand) {
                if (used.contains(h) || isFailed(failed, h)) continue;
                if (beats(h, a) && (bestB == null || cost(h) < cost(bestB))) bestB = h;
            }
            if (bestB == null) { possible = false; break; }
            used.add(bestB);
            planCost += cost(bestB);
            maxCost = Math.max(maxCost, cost(bestB));
            if (bestB.suit == trump && a.suit != trump) usesTrumpOnPlain = true;
            if (firstTarget == null) { firstTarget = a; firstBeater = bestB; }
        }

        // перевод: ещё никто не отбивался и есть карта того же достоинства
        if (!anyDefense && !attacks.isEmpty()) {
            int rank = attacks.get(0).rank;
            boolean sameRank = true;
            for (Card a : attacks) if (a.rank != rank) sameRank = false;
            if (sameRank) {
                Card tr = null;
                for (Card h : st.hand)
                    if (h.rank == rank && !isFailed(failed, h) && (tr == null || cost(h) < cost(tr))) tr = h;
                if (tr != null && tr.suit != trump && (!possible || usesTrumpOnPlain || planCost >= cost(tr) * attacks.size() + 3)) {
                    return new Move(TRANSFER, tr, null, "перевожу");
                }
            }
        }

        if (!possible) return new Move(BUTTON, null, null, "Беру (нечем бить)");

        // не тратим старшие козыри на мелочь, пока есть колода
        int attackSum = 0;
        for (Card a : attacks) attackSum += cost(a);
        if (!deckEmpty && maxCost >= 9 + 6 && attackSum < 8 && st.table.size() <= 2) {
            return new Move(BUTTON, null, null, "Беру (жалко старший козырь)");
        }
        return new Move(DEFEND, firstBeater, firstTarget, "бью");
    }
}
