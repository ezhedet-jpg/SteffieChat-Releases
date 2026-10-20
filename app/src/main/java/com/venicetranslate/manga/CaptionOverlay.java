package com.venicetranslate.manga;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
final class CaptionOverlay extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private List<Dialogue> dialogs = new ArrayList<>();
    private Map<Integer, String> translations;
    private int sourceWidth = 1080, sourceHeight = 2400;
    private final float density;
    CaptionOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        bg.setColor(Color.argb(245, 255, 255, 255));
        text.setColor(Color.rgb(17, 17, 25));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setWillNotDraw(false);
    }
    synchronized void show(List<Dialogue> items, Map<Integer, String> ru, int w, int h) {
        dialogs = new ArrayList<>(items);
        translations = ru;
        sourceWidth = Math.max(1, w);
        sourceHeight = Math.max(1, h);
        postInvalidate();
    }
    synchronized void clear() {
        dialogs = new ArrayList<>();
        translations = null;
        postInvalidate();
    }
    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (translations == null || dialogs.isEmpty()) return;
        final float xScale = (float)getWidth() / sourceWidth;
        final float yScale = (float)getHeight() / sourceHeight;
        float edge = 8 * density;
        for (Dialogue d : dialogs) {
            String value = translations.get(d.id);
            if (value == null || value.trim().isEmpty()) continue;
            Rect src = d.bounds;
            RectF r = new RectF(src.left * xScale, src.top * yScale,
                    src.right * xScale, src.bottom * yScale);
            float preferredWidth = Math.max(r.width() + 22 * density,
                    Math.min(getWidth() * .68f, (float)Math.sqrt(value.length()) * 27 * density));
            int boxWidth = (int)Math.min(getWidth() - 2 * edge, preferredWidth);
            int interiorWidth = Math.max(64, boxWidth - (int)(18 * density));
            StaticLayout layout = null;
            float font = 18 * density;
            float allowedHeight = Math.max(r.height() * 1.8f, 75 * density);
            for (int attempt = 0; attempt < 7; attempt++) {
                text.setTextSize(font);
                layout = StaticLayout.Builder.obtain(value, 0, value.length(), text, interiorWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setLineSpacing(1 * density, 1f)
                        .build();
                if (layout.getHeight() <= allowedHeight || font <= 12 * density) break;
                font -= 1 * density;
            }
            if (layout == null) continue;
            float boxHeight = layout.getHeight() + 18 * density;
            float left = Math.min(getWidth() - edge - boxWidth,
                    Math.max(edge, r.centerX() - boxWidth / 2f));
            float top = Math.min(getHeight() - edge - boxHeight,
                    Math.max(edge, r.centerY() - boxHeight / 2f));
            canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight,
                    10 * density, 10 * density, bg);
            canvas.save();
            canvas.translate(left + (boxWidth - interiorWidth) / 2f, top + 9 * density);
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
