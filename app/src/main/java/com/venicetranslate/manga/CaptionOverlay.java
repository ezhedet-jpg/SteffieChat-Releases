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

/**
 * Russian text is laid out inside compact opaque patches anchored near the OCR text.
 * Each patch occupies its own screen region: never draw two translations on top of
 * one another. Sizes use sp and are adjustable without requesting Venice again.
 */
final class CaptionOverlay extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float scaledDensity;
    private List<Dialogue> dialogs = new ArrayList<>();
    private Map<Integer, String> translations;
    private int sourceWidth = 1080, sourceHeight = 2400;
    private int fontSp = 10;

    private static final class Label {
        final RectF rect;
        final StaticLayout layout;
        final float fontPx;
        final float inset;
        Label(RectF rect, StaticLayout layout, float fontPx, float inset) {
            this.rect = rect;
            this.layout = layout;
            this.fontPx = fontPx;
            this.inset = inset;
        }
    }

    CaptionOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        bg.setColor(Color.argb(252, 255, 255, 255));
        text.setColor(Color.rgb(25, 25, 30));
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        setWillNotDraw(false);
        fontSp = Settings.fontSp(context);
    }

    synchronized void setFontSp(int size) {
        int next = Math.max(7, Math.min(16, size));
        if (fontSp != next) {
            fontSp = next;
            postInvalidate();
        }
    }

    synchronized void show(List<Dialogue> items, Map<Integer, String> ru, int w, int h) {
        if (items == null || ru == null) return;
        // Do not trigger repeated redraws when OCR recognizes an unchanged page.
        boolean same = translations != null && translations.equals(ru)
                && sourceWidth == Math.max(1, w) && sourceHeight == Math.max(1, h)
                && dialogs.size() == items.size();
        if (same) {
            for (int i = 0; i < items.size(); i++) {
                Dialogue a = dialogs.get(i), b = items.get(i);
                if (a.id != b.id || !a.english.equals(b.english)
                        || !a.bounds.equals(b.bounds)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        dialogs = new ArrayList<>(items);
        translations = ru;
        sourceWidth = Math.max(1, w);
        sourceHeight = Math.max(1, h);
        postInvalidate();
    }

    synchronized void clear() {
        if (dialogs.isEmpty() && translations == null) return;
        dialogs = new ArrayList<>();
        translations = null;
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (translations == null || dialogs.isEmpty()) return;
        final float xScale = (float) getWidth() / sourceWidth;
        final float yScale = (float) getHeight() / sourceHeight;
        final List<RectF> occupied = new ArrayList<>();
        for (Dialogue d : dialogs) {
            String value = translations.get(d.id);
            if (value == null || value.trim().isEmpty()) continue;
            Rect r = d.bounds;
            RectF original = new RectF(r.left * xScale, r.top * yScale,
                    r.right * xScale, r.bottom * yScale);
            if (original.isEmpty()) continue;
            Label label = placeLabel(value.trim(), original, occupied);
            if (label == null) continue; // Better an unobstructed page than overlapping labels.
            occupied.add(new RectF(label.rect));
            canvas.drawRoundRect(label.rect, dp(5), dp(5), bg);
            canvas.save();
            canvas.clipRect(label.rect);
            canvas.translate(label.rect.left + label.inset,
                    label.rect.top + label.inset);
            text.setTextSize(label.fontPx);
            label.layout.draw(canvas);
            canvas.restore();
        }
    }

    private Label placeLabel(String value, RectF original, List<RectF> occupied) {
        float margin = dp(5);
        float inset = dp(4);
        float maxWidth = getWidth() - margin * 2;
        if (maxWidth < dp(40)) return null;

        // Do NOT grow tiny English snippets into huge cards that cover the artwork.
        float estimated = (float)Math.sqrt(Math.max(1, value.length())) * dp(11);
        float wanted = Math.max(original.width() + dp(12), estimated);
        wanted = Math.min(getWidth() * .46f, wanted);
        wanted = Math.max(dp(68), wanted);
        float[] widthFactors = { 1f, .79f, 1.14f, .64f };
        float[] offsetsX = { 0f, -.64f, .64f, 0f, 0f, -1.15f, 1.15f, -.65f, .65f };
        float[] offsetsY = { 0f, 0f, 0f, -.88f, .88f, 0f, 0f, -1.65f, 1.65f };

        // Try normal 10sp, then gradually shrink only when needed for the bubble.
        for (float sp = fontSp; sp >= 7f; sp -= 1f) {
            float fontPx = sp * scaledDensity;
            text.setTextSize(fontPx);
            for (float wf : widthFactors) {
                int width = Math.max(Math.round(dp(48)),
                        Math.round(Math.min(maxWidth, wanted * wf)));
                int inside = Math.max(30, width - Math.round(inset * 2));
                StaticLayout layout = StaticLayout.Builder
                        .obtain(value, 0, value.length(), text, inside)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setLineSpacing(0, 1f)
                        .build();
                float h = layout.getHeight() + inset * 2;
                float heightLimit = Math.min(getHeight() * .24f,
                        Math.max(original.height() * 2.8f, dp(66)));
                if (h > heightLimit) continue;

                for (int i = 0; i < offsetsX.length; i++) {
                    float cx = original.centerX() + width * offsetsX[i];
                    float cy = original.centerY() + h * offsetsY[i];
                    float left = clamp(cx - width / 2f, margin,
                            getWidth() - margin - width);
                    float top = clamp(cy - h / 2f, margin,
                            getHeight() - margin - h);
                    RectF candidate = new RectF(left, top, left + width, top + h);
                    if (!collides(candidate, occupied, dp(3))) {
                        return new Label(candidate, layout, fontPx, inset);
                    }
                }
            }
        }
        return null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(Math.max(min, value), Math.max(min, max));
    }

    private static boolean collides(RectF candidate, List<RectF> occupied, float spacing) {
        for (RectF other : occupied) {
            if (candidate.left < other.right + spacing
                    && candidate.right > other.left - spacing
                    && candidate.top < other.bottom + spacing
                    && candidate.bottom > other.top - spacing) return true;
        }
        return false;
    }

    private float dp(float n) { return n * density; }
}
