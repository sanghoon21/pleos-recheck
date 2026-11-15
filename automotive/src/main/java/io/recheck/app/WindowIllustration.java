package io.recheck.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A schematic of the driver's window that follows received positions. */
final class WindowIllustration extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path exterior = new Path();
    private final Path aperture = new Path();
    private final Path door = new Path();
    private final Path mirror = new Path();
    private final Shader glow = new RadialGradient(460, 235, 455,
            new int[]{0x62286382, 0x001b3852}, null, Shader.TileMode.CLAMP);
    private final Shader body = new LinearGradient(290, 240, 550, 465,
            new int[]{0xff698899, 0xff355970, 0xff18354e}, null, Shader.TileMode.CLAMP);
    private float height = .38f;
    private float target = .38f;
    private boolean tracking;
    private ValueAnimator animator;

    WindowIllustration(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        exterior.moveTo(136, 325);
        exterior.lineTo(164, 137);
        exterior.quadTo(175, 114, 213, 91);
        exterior.lineTo(297, 42);
        exterior.quadTo(326, 27, 378, 27);
        exterior.lineTo(622, 30);
        exterior.quadTo(680, 31, 717, 72);
        exterior.lineTo(806, 170);
        exterior.lineTo(791, 326);
        exterior.close();

        aperture.moveTo(165, 307);
        aperture.lineTo(191, 145);
        aperture.lineTo(314, 69);
        aperture.quadTo(342, 54, 382, 54);
        aperture.lineTo(617, 56);
        aperture.quadTo(662, 57, 689, 89);
        aperture.lineTo(775, 187);
        aperture.lineTo(763, 310);
        aperture.close();

        door.moveTo(107, 323);
        door.quadTo(310, 303, 548, 308);
        door.lineTo(802, 313);
        door.quadTo(828, 315, 827, 338);
        door.lineTo(810, 394);
        door.quadTo(802, 426, 750, 431);
        door.lineTo(218, 444);
        door.quadTo(165, 444, 145, 421);
        door.lineTo(99, 365);
        door.quadTo(78, 333, 107, 323);
        door.close();

        mirror.moveTo(748, 211);
        mirror.quadTo(787, 180, 836, 188);
        mirror.quadTo(864, 191, 857, 213);
        mirror.quadTo(848, 237, 817, 243);
        mirror.lineTo(749, 246);
        mirror.close();
    }

    void update(float closedProximity, boolean active) {
        if (tracking != active) {
            tracking = active;
            invalidate();
        }
        if (Math.abs(target - closedProximity) < .002f) return;
        target = closedProximity;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(height, target);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setDuration(230);
        animator.addUpdateListener(value -> {
            height = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 920f, getHeight() / 470f);
        canvas.save();
        canvas.translate((getWidth() - 920f * scale) / 2, (getHeight() - 470f * scale) / 2);
        canvas.scale(scale, scale);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(glow);
        canvas.drawRect(0, 0, 920, 470, paint);
        paint.setShader(null);

        paint.setColor(0x7c08182b);
        canvas.drawOval(110, 406, 824, 463, paint);
        fill(canvas, exterior, 0xff2b4e67);
        stroke(canvas, exterior, 0xff7697ac, 2.5f);
        fill(canvas, aperture, 0xff0c243a);

        canvas.save();
        canvas.clipPath(aperture);
        // The glass top moves toward the roof as the reported position approaches the closed reference.
        float y = 317f - height * 295f;
        paint.setShader(new LinearGradient(160, y, 742, 320,
                new int[]{0xff37748d, 0xff265771, 0xff376d86}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(147, y, 824, 329, paint);
        paint.setShader(null);
        line(canvas, 147, y, 824, y, tracking ? 0xff62dcd4 : 0xffa4ccda, 3.5f);
        line(canvas, 416, y + 6, 203, 335, 0x1ad1ffff, 55);
        line(canvas, 502, y + 6, 291, 335, 0x11d1ffff, 15);
        canvas.restore();

        stroke(canvas, aperture, 0xff65889e, 2.5f);
        fill(canvas, mirror, 0xff426580);
        stroke(canvas, mirror, 0xff829fb2, 2.7f);
        line(canvas, 786, 204, 839, 200, 0xffadced9, 2);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(body);
        canvas.drawPath(door, paint);
        paint.setShader(null);
        stroke(canvas, door, 0xff7595ac, 2.5f);
        line(canvas, 114, 336, 804, 327, 0xff9bb7c7, 2);
        line(canvas, 155, 403, 794, 392, 0x666894ad, 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff142e46);
        canvas.drawRoundRect(592, 351, 692, 372, 9, 9, paint);
        paint.setColor(0xff7196aa);
        canvas.drawRoundRect(607, 353, 684, 361, 4, 4, paint);

        canvas.restore();
    }

    private void fill(Canvas canvas, Path path, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    private void stroke(Canvas canvas, Path path, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas canvas, float startX, float startY, float endX, float endY, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawLine(startX, startY, endX, endY, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
