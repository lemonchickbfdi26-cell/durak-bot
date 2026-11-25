package com.leon.durakbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Служба спец. возможностей: панель поверх игры и сам бот. */
public class BotService extends AccessibilityService {

    static volatile BotService instance;

    private static final int[] SPEEDS = {1, 2, 3};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView statusTv, cardsTv;
    private Button playBtn, speedBtn;
    private int speedIdx = 0;

    private volatile boolean running;
    private final Durak durak = new Durak();

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        speedIdx = getSharedPreferences("cfg", MODE_PRIVATE).getInt("speed", 0);
        showPanel();
        new Thread(DurakVision::init).start(); // заранее готовим образцы
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (panel != null) try { wm.removeView(panel); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= панель =================

    private Button mkBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD14142A);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView handle = new TextView(this);
        handle.setText(" ≡ ");
        handle.setTextSize(22);
        handle.setTextColor(Color.WHITE);
        row.addView(handle);

        playBtn = mkBtn("▶");
        playBtn.setOnClickListener(v -> { if (running) stopBot(); else startBot(); });
        row.addView(playBtn);

        speedBtn = mkBtn(SPEEDS[speedIdx % SPEEDS.length] + "x");
        speedBtn.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            speedBtn.setText(SPEEDS[speedIdx] + "x");
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("speed", speedIdx).apply();
        });
        row.addView(speedBtn);
        panel.addView(row);

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.WHITE);
        statusTv.setTextSize(12);
        statusTv.setMaxWidth(dp(230));
        statusTv.setPadding(dp(2), dp(4), dp(2), dp(2));
        statusTv.setText("Жми ▶, когда сядешь за стол с ботом");
        panel.addView(statusTv);

        cardsTv = new TextView(this);
        cardsTv.setTextColor(0xFFB8E0FF);
        cardsTv.setTextSize(11);
        cardsTv.setMaxWidth(dp(230));
        cardsTv.setPadding(dp(2), 0, dp(2), dp(2));
        panel.addView(cardsTv);

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = getSharedPreferences("cfg", MODE_PRIVATE).getInt("px", 10);
        panelLp.y = getSharedPreferences("cfg", MODE_PRIVATE).getInt("py", 60);

        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int px, py;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); px = panelLp.x; py = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = px + (int) (e.getRawX() - sx);
                        panelLp.y = py + (int) (e.getRawY() - sy);
                        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putInt("px", panelLp.x).putInt("py", panelLp.y).apply();
                        return true;
                }
                return false;
            }
        });
        wm.addView(panel, panelLp);
    }

    private void status(String s) {
        main.post(() -> { if (statusTv != null) statusTv.setText(s); });
    }

    private void showCards(DurakVision.State st) {
        StringBuilder sb = new StringBuilder("Рука: ");
        for (Card c : st.hand) sb.append(c).append(' ');
        sb.append("\nСтол: ");
        for (Card c : st.table) sb.append(c).append(c.covered ? "✓ " : " ");
        sb.append("\nКозырь: ").append(durak.trump < 0 ? "?" : Card.SUITS[durak.trump]);
        if (durak.deckEmpty) sb.append(" (колода пуста)");
        String t = sb.toString();
        main.post(() -> { if (cardsTv != null) cardsTv.setText(t); });
    }

    /** Пока бот двигает карту, панель пропускает касания насквозь. */
    private void panelPassThrough(boolean on) {
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (on) panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                else panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                wm.updateViewLayout(panel, panelLp);
            } catch (Exception ignored) { }
            latch.countDown();
        });
        try { latch.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ================= бот =================

    private void startBot() {
        if (running) return;
        running = true;
        playBtn.setText("⏸");
        new Thread(this::loop, "bot").start();
    }

    private void stopBot() {
        running = false;
        playBtn.setText("▶");
        status("Пауза");
    }

    private int speed() {
        return SPEEDS[speedIdx % SPEEDS.length];
    }

    private static void sleep(long ms) {
        try { Thread.sleep(Math.max(1, ms)); } catch (InterruptedException ignored) { }
    }

    /** Два одинаковых кадра подряд — значит, анимация закончилась. */
    private DurakVision.State readStable(CaptureService cap, long minSeq) {
        long start = System.currentTimeMillis();
        String prevKey = null;
        DurakVision.State prev = null;
        for (int i = 0; i < 40 && running; i++) {
            Frame f = cap.grab();
            if (f == null) { sleep(80); continue; }
            if (f.seq <= minSeq && System.currentTimeMillis() - start < 700) { sleep(40); continue; }
            DurakVision.State s = DurakVision.read(f);
            String k = s.key();
            if (prev != null && k.equals(prevKey)) return s;
            prev = s;
            prevKey = k;
            sleep(Math.max(80, 250 / speed()));
        }
        return null;
    }

    private void loop() {
        Set<String> failed = new HashSet<>();
        String lastKey = null, actedKey = null;
        Card actedCard = null;
        long minSeq = 0, actedAt = 0;
        int waits = 0;
        status("Старт…");
        while (running) {
            CaptureService cap = CaptureService.instance;
            if (cap == null) {
                status("Бот не видит экран: открой приложение бота → кнопка 2");
                sleep(1000);
                continue;
            }
            DurakVision.State st = readStable(cap, minSeq);
            if (!running) break;
            if (st == null) { status("Жду, пока экран успокоится…"); continue; }

            if (st.button == DurakVision.BTN_READY) durak.newGame();
            durak.observe(st);
            showCards(st);

            String key = st.key();
            // после хода ждём, пока игра его покажет (до 2.5 с), и только потом решаем дальше
            if (actedKey != null && key.equals(actedKey) && System.currentTimeMillis() - actedAt < 2500) {
                sleep(150);
                continue;
            }
            // ход так и не сработал (на экране ничего не поменялось) — эту карту больше не пробуем
            if (actedKey != null && key.equals(actedKey) && actedCard != null) failed.add(actedCard.toString());
            if (lastKey == null || !key.equals(lastKey)) {
                if (actedKey == null || !key.equals(actedKey)) failed.clear();
            }
            lastKey = key;
            actedKey = null;
            actedCard = null;

            Durak.Move m = durak.decide(st, failed);
            if (m.type == Durak.WAIT) {
                status("Жду: " + m.why);
                waits++;
                sleep(Math.max(150, 500 / speed()));
                continue;
            }
            waits = 0;
            Frame f = cap.grab();
            if (f == null) continue;
            int sp = speed();
            switch (m.type) {
                case Durak.BUTTON:
                    status("Жму: " + m.why);
                    tap(DurakVision.X(f, 145), DurakVision.Y(f, 1436));
                    break;
                case Durak.ATTACK:
                    status((st.table.isEmpty() ? "Хожу " : "Подкидываю ") + m.card + " (" + m.why + ")");
                    drag(m.card.x, m.card.y, DurakVision.X(f, 360), DurakVision.Y(f, 870), sp);
                    actedCard = m.card;
                    break;
                case Durak.TRANSFER:
                    status("Перевожу " + m.card);
                    drag(m.card.x, m.card.y, DurakVision.X(f, 360), DurakVision.Y(f, 870), sp);
                    actedCard = m.card;
                    break;
                case Durak.DEFEND:
                    status("Бью " + m.target + " картой " + m.card);
                    drag(m.card.x, m.card.y, m.target.x + DurakVision.S(f, 45), m.target.y + DurakVision.S(f, 60), sp);
                    actedCard = m.card;
                    break;
            }
            actedKey = key;
            actedAt = System.currentTimeMillis();
            minSeq = cap.currentSeq();
            sleep(Math.max(250, 900 / sp));
        }
        main.post(() -> { if (playBtn != null) playBtn.setText("▶"); });
    }

    // ================= жесты =================

    private boolean dispatchSync(GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        main.post(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) { ok[0] = true; latch.countDown(); }

                @Override
                public void onCancelled(GestureDescription d) { latch.countDown(); }
            }, null);
            if (!sent) latch.countDown();
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private float[] screen() {
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        return new float[]{real.widthPixels, real.heightPixels};
    }

    private void tap(float x, float y) {
        panelPassThrough(true);
        try {
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x + 1, y);
            dispatchSync(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 70)).build());
        } catch (Exception ignored) { }
        panelPassThrough(false);
    }

    /** Взять карту, подержать, перенести и отпустить. */
    private void drag(float sx, float sy, float ex, float ey, int sp) {
        float[] s = screen();
        ex = Math.max(1, Math.min(s[0] - 2, ex));
        ey = Math.max(1, Math.min(s[1] - 2, ey));
        long hold = Math.max(40, 120 / sp), move = Math.max(120, 380 / sp), end = Math.max(40, 120 / sp);
        panelPassThrough(true);
        try {
            Path p1 = new Path();
            p1.moveTo(sx, sy);
            p1.lineTo(sx + 1, sy);
            GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, hold, true);
            boolean ok = dispatchSync(new GestureDescription.Builder().addStroke(s1).build());
            if (ok) {
                Path p2 = new Path();
                p2.moveTo(sx + 1, sy);
                p2.lineTo(ex, ey);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(p2, 0, move, true);
                ok = dispatchSync(new GestureDescription.Builder().addStroke(s2).build());
                if (ok) {
                    Path p3 = new Path();
                    p3.moveTo(ex, ey);
                    p3.lineTo(ex + 1, ey);
                    ok = dispatchSync(new GestureDescription.Builder().addStroke(s2.continueStroke(p3, 0, end, false)).build());
                }
            }
            if (!ok) {
                Path p = new Path();
                p.moveTo(sx, sy);
                p.lineTo(ex, ey);
                dispatchSync(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, hold + move + end)).build());
            }
        } catch (Exception ignored) { }
        panelPassThrough(false);
    }
}
