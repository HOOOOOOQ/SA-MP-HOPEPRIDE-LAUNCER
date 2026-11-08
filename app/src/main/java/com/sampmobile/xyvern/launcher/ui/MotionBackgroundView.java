package com.hopepride.samp.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The launcher's animated backdrop: a slowly drifting gradient plus a few soft
 * light motes.
 *
 * Design constraints that shaped it:
 *
 *   * it is cheap - one Paint, one Shader rebuilt only when the size changes, and
 *     a fixed small number of motes (12). No allocation per frame, no Bitmap;
 *   * it is lifecycle aware - the caller starts/stops it from
 *     onStart/onStop and onVisibilityChanged, so a backgrounded launcher burns no
 *     frames;
 *   * it honours reduced motion - when disabled it draws one static frame and
 *     never schedules another;
 *   * it never blocks input.
 *
 * The gradient positions are driven by a single time value, so every motes moves
 * on the same clock and the motion reads as one system rather than noise.
 */
public class MotionBackgroundView extends View {

    private static final int MOTE_COUNT = 12;

    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint motePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] moteX = new float[MOTE_COUNT];
    private final float[] moteY = new float[MOTE_COUNT];
    private final float[] moteRadius = new float[MOTE_COUNT];
    private final float[] moteSpeed = new float[MOTE_COUNT];
    private final float[] motePhase = new float[MOTE_COUNT];

    private Shader gradient;
    private int width;
    private int height;

    private boolean running;
    private long startTime;
    private boolean seeded;

    private int topColor = 0xFF0B1420;
    private int bottomColor = 0xFF060B12;
    private int accentColor = 0xFF33B9F7;

    public MotionBackgroundView(Context context) {
        super(context);
        init();
    }

    public MotionBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MotionBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        motePaint.setColor(0x66FFFFFF);
        motePaint.setStyle(Paint.Style.FILL);
    }

    /** Overrides the palette; the view rebuilds its shader on the next frame. */
    public void setColors(int top, int bottom, int accent) {
        this.topColor = top;
        this.bottomColor = bottom;
        this.accentColor = accent;
        this.gradient = null;
        invalidate();
    }

    public void start() {
        if (running) return;
        running = true;
        startTime = SystemClock.uptimeMillis();
        invalidate();
    }

    public void stop() {
        running = false;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) running = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        gradient = null;
        seedMotes(w, h);
    }

    private void seedMotes(int w, int h) {
        if (w <= 0 || h <= 0) return;
        // Deterministic spread: evenly spaced columns with a per-index jitter, so
        // the backdrop looks designed rather than random. Keeping it in a fixed
        // pattern also means the first frame after a rotation looks the same.
        for (int i = 0; i < MOTE_COUNT; i++) {
            float column = (i + 0.5f) / MOTE_COUNT;
            moteX[i] = column * w;
            moteY[i] = ((i * 0.37f) % 1f) * h;
            moteRadius[i] = dp(i % 3 == 0 ? 2.4f : 1.5f);
            moteSpeed[i] = 6f + (i % 4) * 3f;     // dp per second
            motePhase[i] = (i * 0.61f) % 1f;
        }
        seeded = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (width == 0 || height == 0) return;

        if (gradient == null) {
            gradient = new LinearGradient(0, 0, width * 0.35f, height,
                    topColor, bottomColor, Shader.TileMode.CLAMP);
            gradientPaint.setShader(gradient);
        }
        canvas.drawRect(0, 0, width, height, gradientPaint);

        if (!seeded) seedMotes(width, height);
        if (!seeded) return;

        final boolean animate = running && MotionKit.shouldAnimate(getContext());
        final float seconds = animate ? (SystemClock.uptimeMillis() - startTime) / 1000f : 0f;

        // A soft diagonal light band that drifts, giving the gradient depth
        // without a second shader.
        final float bandCenter = ((seconds * 0.02f) % 1f) * (width + height);
        motePaint.setColor(withAlpha(accentColor, 12));
        canvas.save();
        canvas.rotate(-18f, width / 2f, height / 2f);
        canvas.drawRect(bandCenter - width, 0, bandCenter - width * 0.55f, height, motePaint);
        canvas.restore();

        // Motes drift upward and wrap, each at its own speed for parallax.
        motePaint.setColor(withAlpha(0xFFFFFFFF, 56));
        for (int i = 0; i < MOTE_COUNT; i++) {
            float y = moteY[i] - seconds * moteSpeed[i] * getResources().getDisplayMetrics().density;
            if (height > 0) {
                y = y % height;
                if (y < 0) y += height;
            }
            float x = moteX[i] + (float) Math.sin(seconds * 0.4f + motePhase[i] * 6.28f) * dp(4f);
            canvas.drawCircle(x, y, moteRadius[i], motePaint);
        }

        if (animate) postInvalidateOnAnimation();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
