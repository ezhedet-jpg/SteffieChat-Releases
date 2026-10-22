package com.venicetranslate.manga;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Movable controls remain visible above ordinary Chrome while screen capture is active. */
final class FloatingControls extends LinearLayout {
    interface Callbacks {
        void translateNow();
        void setAutomatic(boolean enabled);
        void fontChanged(int fontSp);
        void stop();
    }

    private final TextView dragHandle;
    private final TextView status;
    private final Button automatic;
    private final TextView fontLabel;
    private int fontSp;
    private boolean autoEnabled = true;

    FloatingControls(Context context, Callbacks callbacks, int initialFontSp) {
        super(context);
        fontSp = Math.max(7, Math.min(16, initialFontSp));
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(6), dp(8), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(28, 34, 48));
        background.setCornerRadius(dp(16));
        setBackground(background);
        setElevation(dp(8));

        dragHandle = new TextView(context);
        dragHandle.setText("⋮⋮  Venice RU 0.3   •   перетащить");
        dragHandle.setTextColor(Color.WHITE);
        dragHandle.setTextSize(13);
        dragHandle.setGravity(Gravity.CENTER_VERTICAL);
        dragHandle.setPadding(dp(6), dp(5), dp(6), dp(6));
        addView(dragHandle, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));

        Button translate = makeButton(context, "▶ Перевести", Color.rgb(59, 172, 108));
        row.addView(translate, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.55f));
        translate.setOnClickListener(v -> callbacks.translateNow());

        automatic = makeButton(context, "Авто: ВКЛ", Color.rgb(61, 112, 196));
        LayoutParams autoParams = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.15f);
        autoParams.leftMargin = dp(3);
        row.addView(automatic, autoParams);
        automatic.setOnClickListener(v -> {
            autoEnabled = !autoEnabled;
            setAutoEnabled(autoEnabled);
            callbacks.setAutomatic(autoEnabled);
        });

        Button stop = makeButton(context, "■", Color.rgb(175, 63, 63));
        LayoutParams stopParams = new LayoutParams(0, LayoutParams.MATCH_PARENT, .45f);
        stopParams.leftMargin = dp(3);
        row.addView(stop, stopParams);
        stop.setContentDescription("Остановить переводчик");
        stop.setOnClickListener(v -> callbacks.stop());

        LinearLayout fontRow = new LinearLayout(context);
        fontRow.setOrientation(HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams fontRowParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(38));
        fontRowParams.topMargin = dp(3);
        addView(fontRow, fontRowParams);

        fontLabel = new TextView(context);
        fontLabel.setTextColor(Color.WHITE);
        fontLabel.setTextSize(12);
        fontLabel.setGravity(Gravity.CENTER_VERTICAL);
        fontLabel.setText("Размер: " + fontSp + " sp");
        fontRow.addView(fontLabel, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        Button minus = makeButton(context, "A−", Color.rgb(84, 98, 129));
        fontRow.addView(minus, new LayoutParams(0, LayoutParams.MATCH_PARENT, .5f));
        Button plus = makeButton(context, "A+", Color.rgb(84, 98, 129));
        LayoutParams plusParams = new LayoutParams(0, LayoutParams.MATCH_PARENT, .5f);
        plusParams.leftMargin = dp(4);
        fontRow.addView(plus, plusParams);

        minus.setOnClickListener(v -> {
            if (fontSp > 7) {
                fontSp--;
                fontLabel.setText("Размер: " + fontSp + " sp");
                callbacks.fontChanged(fontSp);
            }
        });
        plus.setOnClickListener(v -> {
            if (fontSp < 16) {
                fontSp++;
                fontLabel.setText("Размер: " + fontSp + " sp");
                callbacks.fontChanged(fontSp);
            }
        });

        status = new TextView(context);
        status.setTextColor(Color.rgb(235, 238, 250));
        status.setTextSize(12);
        status.setText("Готово. Нажми «Перевести» или читай в режиме Авто.");
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(6), dp(6), dp(6), dp(2));
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void attachDrag(WindowManager manager, WindowManager.LayoutParams params, int width, int height) {
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int originX, originY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        originX = params.x;
                        originY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = Math.max(0, Math.min(width - getWidth(),
                                originX + Math.round(event.getRawX() - downX)));
                        params.y = Math.max(0, Math.min(height - getHeight(),
                                originY + Math.round(event.getRawY() - downY)));
                        manager.updateViewLayout(FloatingControls.this, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    void setAutoEnabled(boolean enabled) {
        autoEnabled = enabled;
        automatic.setText(enabled ? "Авто: ВКЛ" : "Авто: ВЫКЛ");
    }

    void setStatus(String message) {
        status.setText(message);
    }

    private static Button makeButton(Context context, String text, int color) {
        Button b = new Button(context);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        return b;
    }

    private int dp(float size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
