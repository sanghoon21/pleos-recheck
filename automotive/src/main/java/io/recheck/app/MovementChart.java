package io.recheck.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import java.util.Locale;

final class MovementChart extends View {
    private static final int MUTED = 0xff455c70;
    private static final int GRID = 0xffd9e3e9;

    private final Observation observation;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trace = new Path();

    MovementChart(Context context, Observation observation, int color) {
        super(context);
        this.observation = observation;
        this.color = color;
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        setContentDescription("창문 위치 변화 그래프. " + observation.result() + ". 위치값 " + observation.points.size() + "개 수신");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 840f, getHeight() / 460f);
        canvas.save();
        canvas.scale(scale, scale);

        float left = 130f;
        float right = 802f;
        float top = 59f;
        float bottom = 355f;
        label(canvas, "닫힘 기준", 0, top + 7f);
        label(canvas, "열림 방향", 0, bottom + 7f);

        for (int line = 0; line < 3; line++) {
            float y = top + (bottom - top) * line / 2f;
            paint.setColor(GRID);
            paint.setStrokeWidth(2);
            canvas.drawLine(left, y, right, y, paint);
        }
        long duration = 1L;
        for (Observation.Point point : observation.points) duration = Math.max(duration, point.elapsedMs);
        if (observation.points.isEmpty()) {
            label(canvas, "수신된 위치값 없음", 310, 210);
            canvas.restore();
            return;
        }

        label(canvas, "0", left, 395);
        String endpoint = String.format(Locale.KOREA, "%.1f초", duration / 1000f);
        label(canvas, endpoint, right - paint.measureText(endpoint), 395);
        label(canvas, "앱 수신 시간", 380, 445);

        trace.reset();
        int index = 0;
        for (Observation.Point point : observation.points) {
            float x = left + (right - left) * Math.max(0, point.elapsedMs) / duration;
            float y = bottom - (bottom - top) * observation.height(point);
            if (index++ == 0) trace.moveTo(x, y);
            else trace.lineTo(x, y);
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(5);
        canvas.drawPath(trace, paint);
        paint.setStyle(Paint.Style.FILL);
        for (Observation.Point point : observation.points) {
            float x = left + (right - left) * Math.max(0, point.elapsedMs) / duration;
            float y = bottom - (bottom - top) * observation.height(point);
            canvas.drawCircle(x, y, 4.7f, paint);
        }
        canvas.restore();
    }

    private void label(Canvas canvas, String value, float left, float baseline) {
        paint.setColor(MUTED);
        paint.setTextSize(23f);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(value, left, baseline, paint);
    }
}
