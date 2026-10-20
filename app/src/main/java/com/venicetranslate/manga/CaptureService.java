package com.venicetranslate.manga;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class CaptureService extends Service {
    public static final String START = "manga.START";
    public static final String STOP = "manga.STOP";
    public static final String CODE = "manga.CODE";
    public static final String PROJECTION_DATA = "manga.PROJECTION_DATA";
    private static final String CHANNEL_ID = "venice_manga_translation";
    private static final int NOTIFY_ID = 7451;
    private static final String TAG = "VeniceManga";
    private static final int MAX_BLOCKS = 16;
    private static final int MAX_TEXT = 1900;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, Map<Integer,String>> cache =
            new LinkedHashMap<String, Map<Integer,String>>(128, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String,Map<Integer,String>> e) {
                    return size() > 160;
                }
            };
    private WindowManager wm;
    private CaptionOverlay captions;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private TextRecognizer recognizer;
    private int screenWidth, screenHeight;
    private boolean stopped, paused, scanning, pending;
    private String shownContent = "", lastContent = "", previousContext = "";
    private int repeated;
    private long retryAfter;
    private int recentInputTokens, recentOutputTokens;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (!paused && !scanning && reader != null) scan();
            ui.postDelayed(this, Settings.interval(CaptureService.this));
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recentInputTokens = Settings.prefs(this).getInt("prompt_tokens", 0);
        recentOutputTokens = Settings.prefs(this).getInt("output_tokens", 0);
        createChannel();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction())) return START_NOT_STICKY;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, notification("Перевод поверх Chrome включён"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else startForeground(NOTIFY_ID, notification("Перевод поверх Chrome включён"));
            if (projection != null) {
                report("Перезапусти перевод после завершения предыдущей сессии");
                stopSelf();
                return START_NOT_STICKY;
            }
            int code = intent.getIntExtra(CODE, 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) {
                data = intent.getParcelableExtra(PROJECTION_DATA, Intent.class);
            } else {
                data = intent.getParcelableExtra(PROJECTION_DATA);
            }
            if (data == null) throw new IllegalStateException("Отсутствует разрешение захвата экрана");
            initProjection(code, data);
        } catch (Exception e) {
            report("Не удалось запустить захват экрана: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void initProjection(int code, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("Android не предоставил доступ к экрану");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (!stopped) {
                    report("Android завершил захват экрана. Включи перевод заново.");
                    stopSelf();
                }
            }
        }, ui);
        virtualDisplay = projection.createVirtualDisplay("VeniceMangaOCR",
                screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, ui);
        createOverlays();
        ui.postDelayed(tick, 600);
    }
    private void createOverlays() {
        captions = new CaptionOverlay(this);
        WindowManager.LayoutParams full = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        full.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(captions, full);
        bubble = new TextView(this);
        bubble.setText("RU");
        bubble.setTextSize(16);
        bubble.setTextColor(Color.WHITE);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(51, 102, 185));
        background.setCornerRadius(dp(35));
        bubble.setBackground(background);
        bubble.setElevation(dp(7));
        bubbleParams = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
        bubbleParams.x = dp(8);
        bubbleParams.y = dp(190);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float initialX, initialY;
            int oldX, oldY;
            long downAt;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = ev.getRawX(); initialY = ev.getRawY();
                        oldX = bubbleParams.x; oldY = bubbleParams.y;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = Math.max(0, Math.min(screenWidth - dp(56),
                                oldX + (int)(ev.getRawX() - initialX)));
                        bubbleParams.y = Math.max(0, Math.min(screenHeight - dp(56),
                                oldY + (int)(ev.getRawY() - initialY)));
                        if (bubble != null) wm.updateViewLayout(bubble, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float distance = (float)Math.hypot(ev.getRawX() - initialX,
                                ev.getRawY() - initialY);
                        if (distance < dp(12)) {
                            if (System.currentTimeMillis() - downAt > 1100) stopSelf();
                            else {
                                paused = !paused;
                                bubble.setText(paused ? "▶" : "RU");
                                if (paused && captions != null) captions.clear();
                                if (!paused) { lastContent = ""; repeated = 0; }
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleParams);
    }
    private int dp(float px) {
        return (int)(px * getResources().getDisplayMetrics().density + .5f);
    }
    private void scan() {
        scanning = true;
        captions.setVisibility(View.INVISIBLE);
        bubble.setVisibility(View.INVISIBLE);
        ui.postDelayed(() -> {
            if (stopped || reader == null) { scanning = false; return; }
            worker.execute(() -> {
                Bitmap bitmap = null;
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) bitmap = bitmapFromImage(image);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to read frame", e);
                } finally {
                    if (image != null) image.close();
                }
                Bitmap finalBitmap = bitmap;
                ui.post(() -> {
                    if (captions != null) captions.setVisibility(View.VISIBLE);
                    if (bubble != null) bubble.setVisibility(View.VISIBLE);
                    if (stopped || finalBitmap == null) {
                        scanning = false;
                        return;
                    }
                    int w = finalBitmap.getWidth(), h = finalBitmap.getHeight();
                    Task<Text> task;
                    try {
                        task = recognizer.process(InputImage.fromBitmap(finalBitmap, 0));
                    } catch (Exception e) {
                        finalBitmap.recycle();
                        scanning = false;
                        report("Ошибка распознавания: " + e.getMessage());
                        return;
                    }
                    task.addOnSuccessListener(text -> {
                        finalBitmap.recycle();
                        processOcr(text, w, h);
                        scanning = false;
                    }).addOnFailureListener(e -> {
                        finalBitmap.recycle();
                        scanning = false;
                        report("Ошибка OCR: " + e.getMessage());
                    });
                });
            });
        }, 180);
    }
    private Bitmap bitmapFromImage(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int padding = rowStride - pixelStride * screenWidth;
        int rawWidth = screenWidth + padding / pixelStride;
        Bitmap raw = Bitmap.createBitmap(rawWidth, screenHeight, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);
        Bitmap cropped;
        if (rawWidth == screenWidth) {
            cropped = raw;
        } else {
            cropped = Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight);
            raw.recycle();
        }
        if (screenWidth <= 1080) return cropped;
        float scale = 1080f / screenWidth;
        Bitmap smaller = Bitmap.createScaledBitmap(cropped, 1080,
                Math.max(1, (int)(screenHeight * scale)), true);
        cropped.recycle();
        return smaller;
    }
    private void processOcr(Text detected, int w, int h) {
        if (stopped) return;
        List<Dialogue> dialogs = new ArrayList<>();
        for (Text.TextBlock block : detected.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null) continue;
            String english = block.getText().replace('\n', ' ').replaceAll("\\s+", " ").trim();
            if (english.length() < 2 || !english.matches("(?s).*[A-Za-z].*")) continue;
            if (box.top < (int)(h * .10f) || box.bottom > (int)(h * .97f)) continue;
            if (box.width() < 15 || box.height() < 10 || english.length() > 380) continue;
            dialogs.add(new Dialogue(0, box, english));
        }
        Collections.sort(dialogs, (a,b) -> {
            int dy = a.bounds.top - b.bounds.top;
            return Math.abs(dy) > 20 ? Integer.compare(a.bounds.top, b.bounds.top)
                    : Integer.compare(a.bounds.left, b.bounds.left);
        });
        List<Dialogue> capped = new ArrayList<>();
        int total = 0;
        for (Dialogue d : dialogs) {
            if (capped.size() >= MAX_BLOCKS || total + d.english.length() > MAX_TEXT) break;
            capped.add(new Dialogue(capped.size(), d.bounds, d.english));
            total += d.english.length();
        }
        StringBuilder sb = new StringBuilder();
        for (Dialogue d : capped) sb.append(d.id).append(':').append(d.english).append('\n');
        String content = sb.toString();
        if (content.isEmpty()) {
            shownContent = lastContent = "";
            repeated = 0;
            captions.clear();
            return;
        }
        if (content.equals(lastContent)) repeated++;
        else {
            lastContent = content;
            repeated = 1;
            if (!content.equals(shownContent)) captions.clear();
        }
        Map<Integer, String> stored = cache.get(content);
        if (stored != null) {
            captions.show(capped, stored, w, h);
            shownContent = content;
            return;
        }
        if (repeated < 2 || pending || paused || System.currentTimeMillis() < retryAfter) return;
        if (!Settings.reserveRequest(this)) {
            report("Достигнут дневной лимит запросов Venice (" + Settings.maxDaily(this) + ")");
            retryAfter = System.currentTimeMillis() + 60_000;
            return;
        }
        pending = true;
        final String requested = content;
        final String previous = previousContext;
        final String apiKey = Settings.loadKey(this);
        final String model = Settings.model(this);
        worker.execute(() -> {
            try {
                VeniceClient.Result result = VeniceClient.translate(apiKey, model, capped, previous);
                ui.post(() -> {
                    pending = false;
                    if (stopped) return;
                    cache.put(requested, result.translations);
                    recentInputTokens += result.promptTokens;
                    recentOutputTokens += result.completionTokens;
                    Settings.prefs(this).edit()
                            .putInt("prompt_tokens", recentInputTokens)
                            .putInt("output_tokens", recentOutputTokens)
                            .apply();
                    StringBuilder ctx = new StringBuilder();
                    for (Dialogue d : capped) {
                        String ru = result.translations.get(d.id);
                        if (ru != null) ctx.append(d.english).append(" → ").append(ru).append("\n");
                    }
                    previousContext = ctx.length() > 850 ? ctx.substring(ctx.length() - 850) : ctx.toString();
                    if (!paused && requested.equals(lastContent)) {
                        captions.show(capped, result.translations, w, h);
                        shownContent = requested;
                    }
                    updateNotification("Venice: " + Settings.callsToday(this)
                            + "/" + Settings.maxDaily(this) + " запросов сегодня");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pending = false;
                    if (!stopped) {
                        retryAfter = System.currentTimeMillis() + 65_000;
                        report("Ошибка Venice: " + e.getMessage());
                    }
                });
            }
        });
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Перевод манги",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Работа переводчика поверх браузера");
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }
    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openAction = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CaptureService.class).setAction(STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Манга / Venice AI")
                .setContentText(message)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAction)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopAction).build())
                .build();
    }
    private void updateNotification(String message) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ID, notification(message));
    }
    private void report(String message) {
        Log.w(TAG, message);
        Settings.rememberError(this, message);
        updateNotification(message);
        if (bubble != null) {
            bubble.setText("!");
            ui.postDelayed(() -> { if (!stopped && bubble != null) bubble.setText(paused ? "▶" : "RU"); }, 4000);
        }
    }
    @Override public void onDestroy() {
        stopped = true;
        ui.removeCallbacksAndMessages(null);
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) {}
        try { if (captions != null) wm.removeView(captions); } catch (Exception ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
