package com.venicetranslate.manga;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 100;
    private static final int REQ_CAPTURE = 101;
    private static final int REQ_NOTIFY = 102;
    private EditText keyField, modelField, delayField, quotaField;
    private TextView status;
    private boolean pendingStart;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(250, 250, 252));
        scroll.addView(root);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Манга → русский · Venice AI");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(25, 25, 35));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));
        text(root, "Оставайся в обычном Chrome. Переводчик увидит открытые реплики, распознает английский текст локально и покажет перевод Venice поверх рисунков.");
        text(root, "Ключ Venice AI (хранится в Android Keystore)");
        keyField = edit(root, "Вставь свой API-ключ", "");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(com.venicetranslate.manga.Settings.loadKey(this));
        text(root, "Модель Venice (только модель, без автоподмены)");
        modelField = edit(root, "Модель", com.venicetranslate.manga.Settings.model(this));
        text(root, "Например: qwen3-235b-a22b-instruct-2507 (рекомендуется) или venice-uncensored-1-2. Наличие модели зависит от твоего API-аккаунта.");
        text(root, "Пауза между проверками экрана, мс (1400–8000)");
        delayField = edit(root, "2400", Integer.toString(com.venicetranslate.manga.Settings.interval(this)));
        delayField.setInputType(InputType.TYPE_CLASS_NUMBER);
        text(root, "Максимум запросов Venice в день (1–500)");
        quotaField = edit(root, "75", Integer.toString(com.venicetranslate.manga.Settings.maxDaily(this)));
        quotaField.setInputType(InputType.TYPE_CLASS_NUMBER);
        Button save = button(root, "Сохранить настройки");
        save.setOnClickListener(v -> store());
        Button start = button(root, "▶ Включить перевод поверх Chrome");
        start.setOnClickListener(v -> {
            if (!store()) return;
            pendingStart = true;
            beginPermissions();
        });
        Button open = button(root, "Открыть Toomics в Chrome");
        open.setOnClickListener(v -> openChrome());
        Button stop = button(root, "■ Остановить переводчик");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, CaptureService.class).setAction(CaptureService.STOP);
            startService(intent);
            Toast.makeText(this, "Переводчик остановлен", Toast.LENGTH_SHORT).show();
        });
        status = new TextView(this);
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(14);
        status.setPadding(0, dp(16), 0, dp(10));
        root.addView(status);
        text(root, "После запуска Android спросит разрешение на запись экрана. Выбери «Весь экран» и открой Chrome. Кнопку RU можно перетаскивать; короткое нажатие — пауза, долгое — остановить.");
        text(root, "ВАЖНО: пока перевод включён, он может распознавать текст любого видимого приложения. Перед открытием личной переписки, банка и настроек ставь перевод на паузу. Скриншоты никуда не загружаются: Venice получает только фразы OCR.");
        text(root, "Приложение не читает cookies Chrome и не обходит платную подписку Toomics.");
        refresh();
    }
    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private void text(LinearLayout r, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.rgb(45, 45, 50));
        t.setTextSize(14);
        t.setPadding(0, dp(8), 0, dp(8));
        r.addView(t);
    }
    private EditText edit(LinearLayout r, String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setHint(hint);
        e.setText(value);
        r.addView(e, new LinearLayout.LayoutParams(-1, dp(55)));
        return e;
    }
    private Button button(LinearLayout r, String s) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.topMargin = dp(8);
        r.addView(b, p);
        return b;
    }
    private int num(EditText e, int fallback, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(e.getText().toString().trim()))); }
        catch (Exception ex) { return fallback; }
    }
    private boolean store() {
        String model = modelField.getText().toString().trim();
        String key = keyField.getText().toString().trim();
        if (model.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Введи ключ API и модель Venice", Toast.LENGTH_LONG).show();
            return false;
        }
        try { com.venicetranslate.manga.Settings.saveKey(this, key); }
        catch (Exception ex) {
            Toast.makeText(this, "Не удалось защитить ключ: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        com.venicetranslate.manga.Settings.prefs(this).edit()
                .putString("model", model)
                .putInt("interval", num(delayField, 2400, 1400, 8000))
                .putInt("max_daily", num(quotaField, 75, 1, 500))
                .apply();
        refresh();
        return true;
    }
    private void refresh() {
        String err = com.venicetranslate.manga.Settings.prefs(this).getString("last_error", "нет");
        status.setText("Запросов сегодня: " + com.venicetranslate.manga.Settings.callsToday(this)
                + "/" + com.venicetranslate.manga.Settings.maxDaily(this)
                + "\nТокенов вход / выход (всего): "
                + com.venicetranslate.manga.Settings.prefs(this).getInt("prompt_tokens", 0)
                + " / " + com.venicetranslate.manga.Settings.prefs(this).getInt("output_tokens", 0)
                + "\nПоследнее сообщение: " + err);
    }
    private void beginPermissions() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        requestCapture();
    }
    private void requestCapture() {
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFY && pendingStart) requestCapture();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_OVERLAY && pendingStart) {
            if (android.provider.Settings.canDrawOverlays(this)) beginPermissions();
            else Toast.makeText(this, "Разреши показ поверх других приложений", Toast.LENGTH_LONG).show();
        }
        if (request == REQ_CAPTURE) {
            pendingStart = false;
            if (result != Activity.RESULT_OK || data == null) {
                Toast.makeText(this, "Захват экрана не разрешён", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent svc = new Intent(this, CaptureService.class).setAction(CaptureService.START);
            svc.putExtra(CaptureService.CODE, result);
            svc.putExtra(CaptureService.PROJECTION_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            openChrome();
        }
    }
    private void openChrome() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(com.venicetranslate.manga.Settings.TOOMICS));
        intent.setPackage("com.android.chrome");
        try { startActivity(intent); }
        catch (Exception ex) {
            intent.setPackage(null);
            try { startActivity(intent); }
            catch (Exception second) { Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show(); }
        }
    }
}
