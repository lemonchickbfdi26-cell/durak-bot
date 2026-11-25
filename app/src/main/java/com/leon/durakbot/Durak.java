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

    // настройки стратегии (подобраны в симуляторе)
    static double P_PAIR = 1.5, P_ACE = 6;
    static int P_GIVE_MAX = 8, P_THROW_MAX = 8, P_THROW_MAX_END = 8, P_BIG_TRUMP = 6, P_TRANSFER_MARGIN = 3;
    static boolean P_TAKE_BIG = false, P_TAKE_RULE2 = false, P_UNB = true, P_SEARCH = true;

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
        if (st.trumpSeen) {
            trump = st.trump;
            deckEmpty = st.deckEmpty; // не «залипает»: видна карта под колодой — колода есть
        }
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

        if (deckEmpty && P_SEARCH) {
            Move e = endgame(st);
            if (e != null) return e;
        }

        if (st.role == DurakVision.ROLE_DEFEND) {
            if (st.unbeaten() == 0) return new Move(WAIT, null, null, "жду ход соперника");
            return defend(st, failed);
        }
        if (st.role == DurakVision.ROLE_ATTACK) {
            if (st.table.isEmpty()) return attack(st, failed);
            if (st.unbeaten() > 0) {
                // соперник ещё отбивается — ждём; подкидываем, только если он сказал «Беру»
                if (!st.oppTakes) return new Move(WAIT, null, null, "соперник отбивается");
                return throwIn(st, failed, true);
            }
            boolean canAct = st.button == DurakVision.BTN_BITO || st.button == DurakVision.BTN_OTHER
                    || st.button == DurakVision.BTN_YOURTURN;
            if (!canAct) return new Move(WAIT, null, null, "соперник думает");
            return throwIn(st, failed, false);
        }
        return new Move(WAIT, null, null, "жду");
    }

    /** Колода пуста: считаем партию до конца (карты соперника известны по памяти). */
    Move endgame(DurakVision.State st) {
        List<Card> opp = unknown(st);
        if (opp.isEmpty() || opp.size() > 16 || st.hand.size() > 16) return null;
        long[] h = {0, 0};
        for (Card c : st.hand) h[0] |= 1L << c.id();
        for (Card c : opp) h[1] |= 1L << c.id();
        Endgame.T t = new Endgame.T();
        int defenses = 0;
        for (Card c : st.table) {
            if (c.isDefense) continue;
            t.A[t.n] = c.id();
            t.D[t.n] = c.coveredBy != null ? c.coveredBy.id() : -1;
            if (c.coveredBy != null) defenses++;
            t.n++;
        }
        Endgame eg = new Endgame(trump);
        int v;
        if (st.role == DurakVision.ROLE_DEFEND) {
            if (t.firstUnbeaten() < 0) return null;
            int limit = Math.min(6, Long.bitCount(h[0]) + defenses);
            v = eg.defenderTurn(h, 1, t, limit, true);
        } else if (st.role == DurakVision.ROLE_ATTACK) {
            int limit = Math.min(6, Long.bitCount(h[1]) + defenses);
            if (t.n == 0) v = eg.roundStart(h, 0, true);
            else if (t.firstUnbeaten() < 0) {
                boolean canAct = st.button == DurakVision.BTN_BITO || st.button == DurakVision.BTN_OTHER
                        || st.button == DurakVision.BTN_YOURTURN;
                if (!canAct) return null;
                v = eg.attackerThrow(h, 0, t, limit, true);
            } else if (st.oppTakes) v = eg.attackerGive(h, 0, t, limit, true);
            else return null;
        } else return null;
        if (eg.overflow || eg.bestType < 0 || v < 0) return null; // не досчитали или всё равно проигрыш — играем по обычным правилам
        String why = v > 0 ? "расчёт: выигрыш" : "расчёт: ничья";
        if (eg.bestType == BUTTON) return new Move(BUTTON, null, null, why);
        Card card = null, target = null;
        for (Card c : st.hand) if (c.id() == eg.bestCard) card = c;
        if (eg.bestType == DEFEND)
            for (Card c : st.table) if (!c.isDefense && c.id() == eg.bestTarget) target = c;
        if (card == null || (eg.bestType == DEFEND && target == null)) return null;
        return new Move(eg.bestType, card, target, why);
    }

    private boolean isFailed(java.util.Set<String> failed, Card c) {
        return failed.contains(c.toString());
    }

    private Move attack(DurakVision.State st, java.util.Set<String> failed) {
        List<Card> hand = new ArrayList<>();
        for (Card c : st.hand) if (!isFailed(failed, c)) hand.add(c);
        if (hand.isEmpty()) return new Move(WAIT, null, null, "все ходы не прошли");

        // колода кончилась — знаем карты соперника: ходим тем, что он не побьёт,
        // но козыри и тузы не тратим, пока у кого-то ещё много карт
        if (deckEmpty && P_UNB) {
            List<Card> opp = unknown(st);
            boolean finish = hand.size() <= 2 || opp.size() <= 2;
            Card bestUnbeatable = null;
            for (Card c : hand) {
                if (!finish && (c.suit == trump || c.rank == 8)) continue;
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
            double sc = cost(c) - (c.suit != trump ? same * P_PAIR : 0) + (c.rank == 8 && !deckEmpty ? P_ACE : 0);
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
                    ok = c.suit != trump && c.rank <= P_GIVE_MAX;
                } else {
                    ok = c.suit != trump && (c.rank <= P_THROW_MAX || (deckEmpty && c.rank <= P_THROW_MAX_END));
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
                if (tr != null && tr.suit != trump && (!possible || usesTrumpOnPlain || planCost >= cost(tr) * attacks.size() + P_TRANSFER_MARGIN)) {
                    return new Move(TRANSFER, tr, null, "перевожу");
                }
            }
        }

        if (!possible) return new Move(BUTTON, null, null, "Беру (нечем бить)");

        // не тратим старшие козыри на мелочь, пока есть колода
        int attackSum = 0;
        for (Card a : attacks) attackSum += cost(a);
        boolean bigTrumpOnPlain = false;
        for (int i = 0; i < used.size(); i++) {
            Card b = used.get(i), a = sorted.get(i);
            if (b.suit == trump && b.rank >= P_BIG_TRUMP && a.suit != trump) bigTrumpOnPlain = true;
        }
        if (P_TAKE_BIG && !deckEmpty && bigTrumpOnPlain && attacks.size() <= 3) {
            return new Move(BUTTON, null, null, "Беру (жалко старший козырь)");
        }
        if (P_TAKE_RULE2 && !deckEmpty && maxCost >= 9 + 6 && attackSum < 8 && st.table.size() <= 2) {
            return new Move(BUTTON, null, null, "Беру (жалко старший козырь)");
        }
        return new Move(DEFEND, firstBeater, firstTarget, "бью");
    }
}
