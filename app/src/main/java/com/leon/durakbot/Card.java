package com.leon.durakbot;

/** Карта: достоинство 0..8 (6,7,8,9,10,В,Д,К,Т), масть 0..3 (♠,♣,♥,♦) и где она на экране. */
final class Card {
    static final String[] RANKS = {"6", "7", "8", "9", "10", "В", "Д", "К", "Т"};
    static final String[] SUITS = {"♠", "♣", "♥", "♦"};

    final int rank, suit;
    float x, y;          // куда тянуть/бросать (центр значка)
    float score;         // уверенность распознавания
    boolean covered;     // на столе: эту карту уже побили
    boolean isDefense;   // на столе: эта карта сама бьёт другую
    Card coveredBy;

    Card(int rank, int suit) {
        this.rank = rank;
        this.suit = suit;
    }

    int id() {
        return suit * 9 + rank;
    }

    boolean same(Card o) {
        return o != null && o.rank == rank && o.suit == suit;
    }

    @Override
    public String toString() {
        return RANKS[rank] + SUITS[suit];
    }
}
