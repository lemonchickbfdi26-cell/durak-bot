package com.leon.durakbot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 42;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Дурак Бот");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        Button acc = new Button(this);
        acc.setText("1. Включить бота в «Специальных возможностях»");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        Button cap = new Button(this);
        cap.setText("2. Разрешить боту видеть экран");
        cap.setOnClickListener(v -> requestCapture());
        root.addView(cap);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setPadding(0, dp(16), 0, 0);
        help.setText(
                "Как пользоваться:\n\n" +
                "1) Нажми первую кнопку, найди «Дурак Бот» (на Samsung: «Установленные службы») и включи.\n\n" +
                "2) Нажми вторую кнопку и разреши запись экрана (на всём экране).\n\n" +
                "3) Открой «Дурак Онлайн» и сядь за стол с ботом: переводной, 36 карт, 2 игрока.\n\n" +
                "4) Сверху появится панель. Жми ▶ — бот сам нажмёт «Готов» и будет играть.\n\n" +
                "Кнопка «1x» меняет скорость. На панели видно, какие карты бот видит у тебя в руке, на столе и какой козырь.\n\n" +
                "Играй ботом только за столом против ботов игры — не против живых людей.");
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean acc = BotService.instance != null;
        boolean cap = CaptureService.instance != null;
        status.setText((acc ? "✅" : "❌") + " Специальные возможности\n"
                + (cap ? "✅" : "❌") + " Бот видит экран\n"
                + (acc && cap ? "\nВсё готово! Открывай «Дурак Онлайн»." : ""));
        status.setTextColor(acc && cap ? Color.rgb(40, 160, 60) : Color.GRAY);
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "Без разрешения бот не увидит игру", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(this, CaptureService.class);
        s.putExtra("code", res);
        s.putExtra("data", data);
        startForegroundService(s);
        Toast.makeText(this, "Готово! Теперь открой «Дурак Онлайн»", Toast.LENGTH_SHORT).show();
        status.postDelayed(this::updateStatus, 700);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
