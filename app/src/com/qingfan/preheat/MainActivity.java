package com.qingfan.preheat;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

/** Schedule screen for cabin preheat on the Dongfeng Box head unit.
 *
 * Layout targets the panel's own geometry: 1920x1080 at density 160, so dp == px.
 * Colours were sampled off the panel itself and off its launcher resources:
 * primary violet #AE83E3, bright violet #B963FD, warm gold #835224, page #F6F5F7.
 *
 * The layout is built in code rather than from XML because this project builds
 * without gradle, where only aapt2 resource linking is available and keeping the
 * UI in one place avoids a second source of truth for the palette.
 */
public class MainActivity extends Activity {

    private static final int VIOLET = 0xFFAE83E3;
    private static final int VIOLET_BRIGHT = 0xFFB963FD;
    private static final int GOLD = 0xFF835224;
    private static final int PAGE = 0xFFF6F5F7;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF2B2733;
    private static final int TEXT_DIM = 0xFF7B7686;

    private static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    private ScheduleStore store;

    private int startH, startM, stopH, stopM;
    private int repeat = 2;
    private int days = 0b0011111;
    private float target;
    private float bandLo, bandHi;
    private boolean defrost = true;
    private boolean auto = false;
    private Button autoBtn, plainBtn;

    private final boolean[] dayBtn = new boolean[7];
    private final Button[] dayViews = new Button[7];
    private TextView startLabel, stopLabel, targetLabel, bandLabel, nowLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ScheduleStore(this);
        startH = store.startHour(); startM = store.startMinute();
        stopH = store.stopHour();   stopM = store.stopMinute();
        repeat = store.repeat();   days = store.days();
        target = store.target();   bandLo = store.bandLo(); bandHi = store.bandHi();
        defrost = store.defrost();
        auto = store.isAuto();
        for (int i = 0; i < 7; i++) dayBtn[i] = (days & (1 << i)) != 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        root.addView(header("Расписание прогрева",
                "Климат включится в указанное время и выключится по окончании."));

        LinearLayout card = card();
        card.addView(label("Время начала"));
        startLabel = label("");
        startLabel.setTextColor(VIOLET);
        text(startLabel, 22);
        startLabel.setGravity(Gravity.CENTER);
        startLabel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickTime(true); } });
        card.addView(startLabel);

        card.addView(label("Время окончания"));
        stopLabel = label("");
        stopLabel.setTextColor(VIOLET);
        text(stopLabel, 22);
        stopLabel.setGravity(Gravity.CENTER);
        stopLabel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickTime(false); } });
        card.addView(stopLabel);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card);

        ScrollView sv = new ScrollView(this);
        sv.addView(wrap);
        sv.setFillViewport(true);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nowCard = card();
        nowCard.addView(label("Сейчас"));
        nowLabel = label("показания ещё не прочитаны");
        nowLabel.setTextColor(TEXT);
        nowLabel.setTextSize(17);
        nowCard.addView(nowLabel);
        LinearLayout nowWrap = new LinearLayout(this);
        nowWrap.setOrientation(LinearLayout.VERTICAL);
        nowWrap.setPadding(0, dp(6), 0, 0);
        nowWrap.addView(nowCard);
        root.addView(nowWrap);

        root.addView(modeRow());

        root.addView(cycleRow());
        root.addView(bandRow());
        root.addView(tempRow());

        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        toggles.setPadding(dp(40), 0, dp(40), dp(10));
        toggles.addView(toggle("Обогрев лобового стекла", defrost, new OnBool() { public void val(boolean v) { defrost = v; } }));
        toggles.addView(toggle("Зеркала и стекло", true, new OnBool() { public void val(boolean v) {
            Toast.makeText(MainActivity.this,
                "На S31 команда не реализована, опция пока не влияет на шину", Toast.LENGTH_SHORT).show();
        }}));
        root.addView(toggles);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(8), 0, dp(10));
        actions.addView(pill("ОКЕЙ", VIOLET_BRIGHT, Color.WHITE, new View.OnClickListener() { public void onClick(View v) { save(); } }));
        actions.addView(pill("ПРОВЕРИТЬ СЕЙЧАС", VIOLET, Color.WHITE, new View.OnClickListener() { public void onClick(View v) { testNow(); } }));
        root.addView(actions);

        setContentView(root);
        refresh();
    }

    private void pickTime(boolean start) {
        int h = start ? startH : stopH;
        int m = start ? startM : stopM;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker v, int hh, int mm) {
                if (start) { startH = hh; startM = mm; } else { stopH = hh; stopM = mm; }
                refresh();
            }
        }, h, m, true).show();
    }

    private void save() {
        store.save(true, startH, startM, stopH, stopM, repeat, days,
                target, bandLo, bandHi, defrost, auto);
        PreheatReceiver.Scheduler.reschedule(this);
        refresh();
        Toast.makeText(this, "Сохранено, будильник поставлен", Toast.LENGTH_SHORT).show();
    }

    /** Runs exactly the sequence the alarm runs, so this button really tests the
     *  shipped path - including moving the setpoint. Earlier this only called
     *  openAc(), which is why the setpoint never changed when testing here. */
    /** Runs exactly the path the alarm runs, with what is on screen right now -
     *  not the last saved values. Same entry point as PreheatReceiver. */
    private void testNow() {
        final CanClient can = new CanClient(this);
        CanCallback cb = new CanCallback(new CanCallback.Listener() {
            public void onAirCondition(boolean acOn, int cabinTemp, float setpoint) {
                if (!acOn && PreheatLoop.isActive()) {
                    log("выключили A/C -> завершаю прогрев");
                    PreheatLoop.finish(can);
                }
            }
            public void onAccChanged(int acc) { }
            public void onVehicleStateResponse(boolean accepted) { }
        });
        PreheatStarter.start(this, can, cb, target, auto, defrost);
    }

    /** Shows what the car actually reports. The setpoint and the ambient value are
     *  the only two we can read right now: the measured cabin temperature is not
     *  exposed anywhere in the AIDL yet, so we do not pretend to show it. */
    private void showNow(CanClient.AirState st) {
        StringBuilder sb = new StringBuilder();
        sb.append("в салоне: ").append(fmtTemp(st.cabinTemp));
        sb.append("\nна улице: ").append(fmtTemp(st.outsideTemp));
        sb.append("\nуставка:   ").append(Float.isNaN(st.setpoint) ? "?" : st.setpoint + " °C");
        if (nowLabel != null) nowLabel.setText(sb.toString());
    }

    /** airTempInCar / airTempOutCar are plain whole degrees: measured on the car,
     *  airTempOutCar returned 17 while the head unit showed 17 °C. No scaling.
     *  -1 is the vendor's "not reported" sentinel, and on this box airTempInCar
     *  is always -1 - the cabin temperature simply is not fed to the unit. */
    private static String fmtTemp(int raw) {
        if (raw == Integer.MIN_VALUE || raw == -1) return "не передаётся";
        return raw + " °C";
    }

    private void refresh() {
        startLabel.setText(String.format("%02d:%02d", startH, startM));
        stopLabel.setText(String.format("%02d:%02d", stopH, stopM));
        targetLabel.setText(String.format("%.1f °C", target));
        bandLabel.setText(String.format("%.0f °C  …  %.0f °C", bandLo, bandHi));
        for (int i = 0; i < 7; i++) paintDay(i);
        log(store.describe());
    }

    /** Screen-visible logging was removed: the text was unreadable at driving
     *  distance and nothing would ever clear it. Everything goes to logcat under
     *  the Preheat tag instead, which is what adb logcat -s Preheat reads. */
    private void log(String s) {
        Log.i(CanClient.TAG, "UI: " + s);
    }

    /** AUTO hands the closed loop to the car; the plain mode drives the setpoint
     *  ourselves. Both proven to be accepted by the service on 2026-10-04. */
    private LinearLayout modeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(8), dp(14), 0);
        row.setBackground(roundRect(CARD, 20));
        plainBtn = pill("ОБЫЧНЫЙ", CARD, TEXT, new View.OnClickListener() {
            public void onClick(View v) { auto = false; paintMode(); }
        });
        autoBtn = pill("AUTO", VIOLET, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { auto = true; paintMode(); }
        });
        row.addView(plainBtn);
        row.addView(autoBtn);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        p.setMargins(dp(14), dp(10), dp(14), 0);
        row.setLayoutParams(p);
        paintMode();
        return row;
    }

    private void paintMode() {
        if (plainBtn == null) return;
        plainBtn.setBackground(roundRect(auto ? CARD : VIOLET, 22));
        plainBtn.setTextColor(auto ? TEXT : Color.WHITE);
        autoBtn.setBackground(roundRect(auto ? VIOLET : CARD, 22));
        autoBtn.setTextColor(auto ? Color.WHITE : TEXT);
    }

    private LinearLayout cycleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(8), dp(14), 0);
        row.addView(pill(repeat == 0 ? "ОДИН РАЗ" : "КАЖДЫЙ ДЕНЬ", VIOLET, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) {
                if (repeat == 0) { repeat = 1; } else if (repeat == 1) { repeat = 2; } else { repeat = 0; }
                refresh();
            }
        }));
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            dayViews[i] = pill(DAYS[i], CARD, TEXT, new View.OnClickListener() {
                public void onClick(View v) {
                    dayBtn[idx] = !dayBtn[idx];
                    int d = 0;
                    for (int k = 0; k < 7; k++) if (dayBtn[k]) d |= 1 << k;
                    days = d;
                    paintDay(idx);
                }
            });
            row.addView(dayViews[i]);
        }
        return row;
    }

    private void paintDay(int i) {
        Button b = dayViews[i];
        if (b == null) return;
        b.setTextColor(dayBtn[i] ? Color.WHITE : TEXT_DIM);
        b.setBackground(roundRect(dayBtn[i] ? VIOLET_BRIGHT : CARD, 22));
    }

    private LinearLayout bandRow() {
        LinearLayout card = card();
        card.addView(label("Мёртвая зона салона — ниже «от» включаем, выше «до» выключаем"));
        bandLabel = label("");
        bandLabel.setTextColor(TEXT);
        text(bandLabel, 16);
        card.addView(bandLabel);
        card.addView(slider("от, °C", 0, 30, 1, bandLo, new OnVal() { public void val(float v) { bandLo = v; refresh(); } }));
        card.addView(slider("до, °C", 0, 30, 1, bandHi, new OnVal() { public void val(float v) { bandHi = v; refresh(); } }));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(card);
        return wrap;
    }

    private LinearLayout tempRow() {
        LinearLayout card = card();
        card.addView(label("Целевая температура"));
        targetLabel = label("");
        targetLabel.setTextColor(TEXT);
        text(targetLabel, 17);
        card.addView(targetLabel);
        card.addView(slider("температура", 150, 320, 1, target * 10, new OnVal() { public void val(float v) { target = v / 10f; refresh(); } }));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(card);
        return wrap;
    }

    private LinearLayout slider(String name, int min, int max, float scale, float value, OnVal f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(name);
        text(t, 11);
        row.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.round(value * scale) - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { f.val(p / scale + min); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        return row;
    }

    interface OnVal { void val(float v); }

    private View toggle(String name, boolean initial, OnBool f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.setBackground(roundRect(CARD, 14));
        TextView t = label(name);
        text(t, 11);
        row.addView(t);
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton v, boolean on) { f.val(on); } });
        row.addView(sw);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.setLayoutParams(p);
        return row;
    }

    interface OnBool { void val(boolean v); }

    private View header(String title, String sub) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(CARD);
        v.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView t = label(title);
        text(t, 20);
        t.setTextColor(TEXT);
        v.addView(t);
        TextView s = label(sub);
        text(s, 18);
        s.setTextColor(TEXT_DIM);
        v.addView(s);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(CARD, 20));
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(14), dp(6), dp(14), 0);
        c.setLayoutParams(p);
        return c;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT_DIM);
        text(t, 11);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private Button pill(String text, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        text(b, 11);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundRect(bg, 22));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        p.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(p);
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(1, 0x22000000);
        return g;
    }

    /** Text size in ABSOLUTE PIXELS.
     *
     * The head unit runs an enlarged system font scale, so setTextSize() without a
     * unit resolves against scaledDensity and rendered roughly three times larger
     * than the number asked for. That made the first build look oversized. Pinning
     * to PX makes the layout match the panel's own pixels, which is the whole point
     * of targeting 1920x1080 at density 160. */
    private void text(TextView t, float px) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    /** Layout units are absolute pixels: the panel is 1920x1080 at density 160,
     * so one dp is already one px and a separate scale factor only desynchronised
     * paddings from control heights. */
    private int dp(int v) {
        return v;
    }

}
