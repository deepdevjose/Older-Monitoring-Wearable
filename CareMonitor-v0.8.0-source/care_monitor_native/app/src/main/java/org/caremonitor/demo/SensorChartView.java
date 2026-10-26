package org.caremonitor.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free, accessible charts for live and demonstration readings.
 * Missing/non-finite readings leave gaps; they are never converted into zero.
 * Callers must identify illustrative histories and raw sensor units in the card.
 */
public final class SensorChartView extends View {
    private static final int LINE = 0, BARS = 1, RING = 2, EMPTY = 3, SPARK = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();
    private int mode;
    private double[] values = new double[0];
    private String[] labels = new String[0];
    private Double value;
    private double maximum;
    private String unit = "";
    private String earlierLabel="-60 min", middleLabel="-30 min", latestLabel="Now";
    private boolean recentTimeline;

    /** Live samples use relative ordering rather than pretending to span an hour. */
    public SensorChartView timeline(String earlier,String middle,String latest) {
        String nextEarlier=safe(earlier), nextMiddle=safe(middle), nextLatest=safe(latest);
        if(recentTimeline && earlierLabel.equals(nextEarlier) && middleLabel.equals(nextMiddle)
                && latestLabel.equals(nextLatest)) return this;
        earlierLabel=nextEarlier;middleLabel=nextMiddle;latestLabel=nextLatest;
        recentTimeline=true;
        refreshDescription();
        invalidate();return this;
    }
    private String message = "No valid readings available";
    private int accent, textColor, mutedColor, gridColor;

    private SensorChartView(Context context, int kind, int color, int text,
                            int muted, int grid) {
        super(context);
        mode = kind;
        accent = color;
        textColor = text;
        mutedColor = muted;
        gridColor = grid;
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumWidth(dp(160));
    }

    public static SensorChartView line(Context context, double[] values, int color,
                                       String unit, int text, int muted, int grid) {
        SensorChartView chart = new SensorChartView(context, LINE, color, text, muted, grid);
        chart.values = values == null ? new double[0] : values.clone();
        chart.unit = safe(unit);
        chart.refreshDescription();
        return chart;
    }

    public static SensorChartView bars(Context context, double[] values, String[] labels,
                                       int color, String unit, int text, int muted, int grid) {
        SensorChartView chart = new SensorChartView(context, BARS, color, text, muted, grid);
        chart.values = values == null ? new double[0] : values.clone();
        chart.labels = labels == null ? new String[0] : labels.clone();
        chart.unit = safe(unit);
        chart.refreshDescription();
        return chart;
    }

    public static SensorChartView spark(Context context, double[] values, int color,
                                        String unit, int text, int muted, int grid) {
        SensorChartView chart = new SensorChartView(context, SPARK, color, text, muted, grid);
        chart.values = values == null ? new double[0] : values.clone();
        chart.unit = safe(unit);
        chart.refreshDescription();
        return chart;
    }

    public static SensorChartView ring(Context context, Double value, double maximum,
                                       String unit, int color, int text, int muted, int grid) {
        SensorChartView chart = new SensorChartView(context, RING, color, text, muted, grid);
        chart.value = value;
        chart.maximum = maximum;
        chart.unit = safe(unit);
        chart.refreshDescription();
        return chart;
    }

    public static SensorChartView empty(Context context, String message, int text,
                                        int muted, int grid) {
        SensorChartView chart = new SensorChartView(context, EMPTY, muted, text, muted, grid);
        chart.message = missingMessage(message);
        chart.refreshDescription();
        return chart;
    }

    /** Replace a line or sparkline's data without replacing or measuring its View. */
    public SensorChartView updateSeries(double[] samples) {
        double[] next = samples == null ? new double[0] : samples;
        if (Arrays.equals(values, next)) return this;
        values = next.clone();
        refreshDescription();
        invalidate();
        return this;
    }

    /** Replace bar data and labels, retaining the chart's dimensions and focus. */
    public SensorChartView updateBars(double[] samples, String[] sampleLabels) {
        double[] next = samples == null ? new double[0] : samples;
        String[] nextLabels = sampleLabels == null ? new String[0] : sampleLabels;
        if (Arrays.equals(values, next) && Arrays.equals(labels, nextLabels)) return this;
        values = next.clone();
        labels = nextLabels.clone();
        refreshDescription();
        invalidate();
        return this;
    }

    /** Replace a ring's reading/scale; null and non-finite readings stay unavailable. */
    public SensorChartView updateRing(Double reading, double scaleMaximum) {
        if (sameNumber(value, reading) && Double.compare(maximum, scaleMaximum) == 0) return this;
        value = reading;
        maximum = scaleMaximum;
        refreshDescription();
        invalidate();
        return this;
    }

    /** Set the missing-state explanation without changing this chart's kind. */
    public SensorChartView updateMessage(String explanation) {
        String next = missingMessage(explanation);
        if (message.equals(next)) return this;
        message = next;
        refreshDescription();
        if (mode == EMPTY || (mode != RING && mode != SPARK && !hasValues())) invalidate();
        return this;
    }

    private void refreshDescription() {
        String description;
        if (mode == RING) {
            description = validRing() ? format(value) + " " + unit : "Unavailable";
            if (finite(maximum) && maximum > 0) description += "; scale 0 to " + format(maximum);
        } else if (mode == BARS) {
            description = barDescription();
        } else if (mode == EMPTY) {
            description = "No data. " + message;
        } else {
            description = lineDescription();
        }
        // Numeric updates may render the same spoken value after rounding. Avoid
        // sending duplicate accessibility content-change events for those frames.
        CharSequence previous = getContentDescription();
        if (previous == null || !description.contentEquals(previous)) setContentDescription(description);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int base = mode == LINE ? 190 : mode == BARS ? 200 : mode == RING ? 140 : mode == SPARK ? 48 : 120;
        float fontScale = getResources().getConfiguration().fontScale;
        int extra = Math.round(Math.max(0f, fontScale - 1f) * (mode == SPARK ? 16 : mode == RING ? 60 : 80));
        int desired = dp(base + extra) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(dp(280) + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(), desired), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + dp(4);
        float right = getWidth() - getPaddingRight() - dp(4);
        float top = getPaddingTop() + dp(4);
        float bottom = getHeight() - getPaddingBottom() - dp(4);
        if (right <= left || bottom <= top) return;
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        if (mode == SPARK) {
            drawSpark(canvas, left, top, right, bottom);
        } else if (mode == EMPTY || (mode != RING && !hasValues())) {
            drawEmpty(canvas, left, top, right, bottom);
        } else if (mode == LINE) {
            drawLine(canvas, left, top, right, bottom);
        } else if (mode == BARS) {
            drawBars(canvas, left, top, right, bottom);
        } else {
            drawRing(canvas, left, top, right, bottom);
        }
        canvas.restore();
    }

    private void drawSpark(Canvas canvas, float left, float top, float right, float bottom) {
        if (!hasValues()) {
            prepare(mutedColor, 11, false);
            drawFitted(canvas, "Unavailable", left, (top + bottom) / 2 + sp(4), right - left, false);
            return;
        }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (double sample : values) if (finite(sample)) {
            low = Math.min(low, sample);
            high = Math.max(high, sample);
        }
        double spread = high - low;
        double padding = spread == 0 ? Math.max(1, Math.abs(high) * .04) : spread * .1;
        low -= padding;
        high += padding;
        float startX = left + dp(4), endX = right - dp(4);
        float startY = top + dp(4), endY = bottom - dp(4);
        path.reset();
        boolean started = false;
        float latestX = 0, latestY = 0;
        for (int i = 0; i < values.length; i++) {
            if (!finite(values[i])) { started = false; continue; }
            float x = values.length <= 1 ? (startX + endX) / 2 :
                    startX + (endX - startX) * i / (values.length - 1f);
            float y = position(values[i], low, high, startY, endY);
            if (started) path.lineTo(x, y); else path.moveTo(x, y);
            started = true;
            if ((i == 0 || !finite(values[i - 1]))
                    && (i == values.length - 1 || !finite(values[i + 1]))) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                canvas.drawCircle(x, y, dp(2.5f), paint);
            }
            latestX = x;
            latestY = y;
        }
        stroke(accent, 2.4f);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(latestX, latestY, dp(3), paint);
    }

    private void drawLine(Canvas canvas, float left, float top, float right, float bottom) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (double sample : values) if (finite(sample)) {
            low = Math.min(low, sample);
            high = Math.max(high, sample);
        }
        double gap = high - low;
        double padding = gap == 0 ? Math.max(1.0, Math.abs(high) * 0.04) : gap * 0.15;
        low -= padding;
        high += padding;
        prepare(mutedColor, 11, false);
        float labelWidth = Math.max(paint.measureText(format(low)), paint.measureText(format(high)));
        float plotLeft = left + Math.min(labelWidth, (right - left) * 0.30f) + dp(10);
        float plotRight = right - dp(5);
        float plotTop = top + sp(14) + dp(10);
        float plotBottom = bottom - sp(12) - dp(14);
        drawHeader(canvas, left, top, right, unit);
        if (plotRight <= plotLeft || plotBottom <= plotTop) return;
        for (int tick = 0; tick <= 2; tick++) {
            float y = plotTop + (plotBottom - plotTop) * tick / 2f;
            stroke(gridColor, 1);
            canvas.drawLine(plotLeft, y, plotRight, y, paint);
            prepare(mutedColor, 11, false);
            drawFitted(canvas, format(high - (high - low) * tick / 2), left,
                    y + textHeight() * .3f, plotLeft - left - dp(7), false);
        }
        path.reset();
        boolean started = false;
        int latestIndex = -1;
        float latestX = 0, latestY = 0;
        for (int i = 0; i < values.length; i++) {
            if (!finite(values[i])) { started = false; continue; }
            float x = values.length <= 1 ? (plotLeft + plotRight) / 2 :
                    plotLeft + (plotRight - plotLeft) * i / (values.length - 1f);
            float y = position(values[i], low, high, plotTop, plotBottom);
            if (started) path.lineTo(x, y); else path.moveTo(x, y);
            started = true;
            if ((i == 0 || !finite(values[i - 1]))
                    && (i == values.length - 1 || !finite(values[i + 1]))) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                canvas.drawCircle(x, y, dp(3), paint);
            }
            latestIndex = i;
            latestX = x;
            latestY = y;
        }
        stroke(accent, 2.6f);
        canvas.drawPath(path, paint);
        if (latestIndex >= 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(latestX, latestY, dp(4), paint);
        }
        if (values.length <= 1) {
            prepare(mutedColor, 11, false);
            drawFitted(canvas, "Latest", plotLeft, bottom - dp(3), plotRight - plotLeft, true);
        } else {
            drawTimeLabels(canvas, plotLeft, plotRight, bottom - dp(3));
        }
    }

    private void drawBars(Canvas canvas, float left, float top, float right, float bottom) {
        boolean history = denseHistory();
        double low = 0, high = 0;
        for (double sample : values) if (finite(sample)) {
            low = Math.min(low, sample);
            high = Math.max(high, sample);
        }
        if (high == low) high = 1;
        double range = high - low;
        if (high > 0) high += range * .18;
        if (low < 0) low -= range * .18;
        prepare(mutedColor, 11, false);
        float labelWidth = Math.max(paint.measureText(format(low)), paint.measureText(format(high)));
        float plotLeft = left + Math.min(labelWidth, (right - left) * .30f) + dp(10);
        float plotRight = right - dp(4);
        float plotTop = top + sp(14) + dp(14);
        float plotBottom = bottom - sp(13) - dp(12);
        drawHeader(canvas, left, top, right, unit);
        if (plotRight <= plotLeft || plotBottom <= plotTop) return;
        float zeroY = position(0, low, high, plotTop, plotBottom);
        for (int tick = 0; tick <= 2; tick++) {
            double axis = high - (high - low) * tick / 2;
            float y = position(axis, low, high, plotTop, plotBottom);
            stroke(gridColor, 1);
            canvas.drawLine(plotLeft, y, plotRight, y, paint);
            prepare(mutedColor, 11, false);
            drawFitted(canvas, format(axis), left, y + textHeight() * .3f,
                    plotLeft - left - dp(7), false);
        }
        stroke(mutedColor, 1);
        canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, paint);
        float cell = (plotRight - plotLeft) / Math.max(1, values.length);
        float width = Math.min(dp(46), cell * .52f);
        for (int i = 0; i < values.length; i++) {
            float center = plotLeft + cell * (i + .5f);
            if (finite(values[i])) {
                float y = position(values[i], low, high, plotTop, plotBottom);
                paint.setColor(accent);
                paint.setStyle(Paint.Style.FILL);
                if (values[i] == 0) {
                    canvas.drawCircle(center, zeroY, dp(2.2f), paint);
                } else {
                    canvas.drawRect(center - width / 2, Math.min(y, zeroY),
                            center + width / 2, Math.max(y, zeroY), paint);
                }
                if (!history) {
                    prepare(textColor, 11, true);
                    float numberY = values[i] < 0 ? y + sp(12) + dp(2) : y - dp(5);
                    numberY = Math.max(plotTop + sp(11), Math.min(plotBottom - dp(2), numberY));
                    drawFitted(canvas, format(values[i]), center - cell / 2, numberY, cell, true);
                }
            } else if (!history) {
                prepare(mutedColor, 11, false);
                drawFitted(canvas, "--", center - cell / 2, zeroY - dp(7), cell, true);
            }
            if (!history) {
                prepare(mutedColor, 11, false);
                drawFitted(canvas, barLabel(i), center - cell / 2, bottom - dp(3), cell - dp(3), true);
            }
        }
        if (history) drawTimeLabels(canvas, plotLeft, plotRight, bottom - dp(3));
    }

    private void drawTimeLabels(Canvas canvas, float left, float right, float baseline) {
        prepare(mutedColor, 11, false);
        canvas.drawText(earlierLabel, left, baseline, paint);
        String latest = latestLabel;
        canvas.drawText(latest, right - paint.measureText(latest), baseline, paint);
        if (right - left > sp(170)) {
            String middle = middleLabel;
            canvas.drawText(middle, (left + right - paint.measureText(middle)) / 2, baseline, paint);
        }
    }

    private void drawRing(Canvas canvas, float left, float top, float right, float bottom) {
        float centerX = (left + right) / 2;
        float centerY = (top + bottom) / 2;
        float radius = Math.min((right - left) / 2 - dp(10), (bottom - top) / 2 - dp(10));
        radius = Math.max(dp(12), radius);
        arc.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        stroke(gridColor, 7);
        canvas.drawArc(arc, -90, 360, false, paint);
        if (validRing() && finite(maximum) && maximum > 0) {
            float portion = (float)Math.max(0, Math.min(1, value / maximum));
            stroke(accent, 7);
            canvas.drawArc(arc, -90, portion * 360, false, paint);
        }
        String number = validRing() ? format(value) : "--";
        prepare(validRing() ? textColor : mutedColor, 25, true);
        float numberWidth = Math.max(1, radius * 1.65f);
        // The value stays within the ring even with a large accessibility font.
        if (paint.measureText(number) > numberWidth) {
            paint.setTextSize(Math.max(sp(13), paint.getTextSize() * numberWidth / paint.measureText(number)));
        }
        drawFitted(canvas, number, centerX - radius, centerY - dp(1), radius * 2, true);
        prepare(mutedColor, 11, false);
        drawFitted(canvas, unit, centerX - radius + dp(7), centerY + sp(16), radius * 2 - dp(14), true);
    }

    private void drawEmpty(Canvas canvas, float left, float top, float right, float bottom) {
        String title = "No data";
        prepare(textColor, 15, true);
        float centerY = (top + bottom) / 2;
        drawFitted(canvas, title, left + dp(6), centerY - sp(11), right - left - dp(12), true);
        prepare(mutedColor, 12, false);
        List<String> lines = wrap(message, Math.max(dp(20), right - left - dp(16)), 3);
        float y = centerY + sp(13);
        for (String line : lines) {
            drawFitted(canvas, line, left + dp(8), y, right - left - dp(16), true);
            y += sp(17);
        }
    }

    private void drawHeader(Canvas canvas, float left, float top, float right, String header) {
        prepare(mutedColor, 11, false);
        drawFitted(canvas, header, left, top - paint.getFontMetrics().ascent, right - left, false);
    }

    private void prepare(int color, float sizeSp, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(sp(sizeSp));
        paint.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void stroke(int color, float widthDp) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(dp(widthDp));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void drawFitted(Canvas canvas, String value, float left, float baseline,
                            float width, boolean centered) {
        if (width <= 0) return;
        String shown = safe(value);
        if (paint.measureText(shown) > width) {
            int count = paint.breakText(shown, true, Math.max(0, width - paint.measureText("...")), null);
            shown = count > 0 ? shown.substring(0, count) + "..." : "";
        }
        canvas.drawText(shown, centered ? left + (width - paint.measureText(shown)) / 2 : left,
                baseline, paint);
    }

    private List<String> wrap(String value, float width, int maxLines) {
        List<String> result = new ArrayList<>();
        String rest = safe(value).trim();
        while (!rest.isEmpty() && result.size() < maxLines) {
            int count = paint.breakText(rest, true, width, null);
            if (count <= 0) break;
            if (count == rest.length()) { result.add(rest); break; }
            if (result.size() == maxLines - 1) { result.add(rest); break; }
            int split = rest.lastIndexOf(' ', count);
            if (split <= 0) split = count;
            result.add(rest.substring(0, split));
            rest = rest.substring(split).trim();
        }
        return result;
    }

    private String lineDescription() {
        int valid = 0;
        Double latest = null;
        for (double sample : values) if (finite(sample)) { valid++; latest = sample; }
        if (latest == null) return "Trend chart. " + message + ". " + unit;
        return (recentTimeline ? "Recent received trend. Latest available value "
                : "Illustrative 60-minute trend. Latest available value ") + format(latest) + " " + unit
                + ". " + valid + " valid samples; " + (values.length - valid) + " unavailable.";
    }

    private String barDescription() {
        StringBuilder result = new StringBuilder(denseHistory()
                ? (recentTimeline ? "Recent received bar chart, " : "Illustrative 60-minute bar chart, ")
                : "Bar chart, ").append(unit).append(". ");
        for (int i = 0; i < values.length; i++) {
            result.append(barLabel(i)).append(": ")
                    .append(finite(values[i]) ? format(values[i]) : "unavailable").append(". ");
        }
        if (!hasValues()) result.append(message).append('.');
        return result.toString();
    }

    private String barLabel(int index) {
        return index < labels.length && labels[index] != null && !labels[index].trim().isEmpty()
                ? labels[index] : "Sample " + (index + 1);
    }

    private boolean denseHistory() { return labels.length == 0 && values.length > 4; }

    private boolean hasValues() {
        for (double sample : values) if (finite(sample)) return true;
        return false;
    }
    private boolean validRing() { return value != null && finite(value); }
    private static boolean sameNumber(Double left, Double right) {
        return left == right || (left != null && right != null && Double.compare(left, right) == 0);
    }
    private static String missingMessage(String message) {
        return message == null || message.trim().isEmpty() ? "No valid readings available" : message;
    }
    private static boolean finite(double number) { return !Double.isNaN(number) && !Double.isInfinite(number); }
    private static String safe(String value) { return value == null ? "" : value; }
    private float textHeight() { return paint.getFontMetrics().descent - paint.getFontMetrics().ascent; }
    private int dp(float number) { return Math.round(number * getResources().getDisplayMetrics().density); }
    private float sp(float number) { return number * getResources().getDisplayMetrics().scaledDensity; }
    private static float position(double value, double low, double high, float top, float bottom) {
        return (float)(bottom - (value - low) / (high - low) * (bottom - top));
    }
    private static String format(double number) {
        if (!finite(number)) return "Unavailable";
        double magnitude = Math.abs(number);
        if (magnitude >= 10000 || (magnitude > 0 && magnitude < .001))
            return String.format(Locale.US, "%.1e", number);
        if (magnitude >= 100 || number == Math.rint(number)) return String.format(Locale.US, "%.0f", number);
        if (magnitude >= 10) return String.format(Locale.US, "%.1f", number);
        return String.format(Locale.US, "%.2f", number);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.TextView");
        info.setClickable(false);
    }
}
