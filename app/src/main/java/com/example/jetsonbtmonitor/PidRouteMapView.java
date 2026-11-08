package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Route-only map-frame visualization.
 *
 * The route is the actual XY path sent by the NUC; the chair is the current
 * /fast_lio_icp/pose. No PGM, external map image or fake emulator geometry.
 * The ROS map frame uses +Y up; Android Canvas uses +Y down.
 */
public final class PidRouteMapView extends View {
    private static final int NAVY = Color.rgb(31, 49, 68);
    private static final int BLUE = Color.rgb(0, 111, 209);
    private static final int GREEN = Color.rgb(17, 137, 92);
    private static final int MUTED = Color.rgb(119, 132, 148);
    private static final int GRID = Color.rgb(232, 238, 245);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();
    private final Path arrowPath = new Path();
    private float[] xs = new float[0];
    private float[] ys = new float[0];
    private boolean hasPose;
    private float poseX, poseY, poseYaw;
    private String routeName = "";

    public PidRouteMapView(Context context) { super(context); }
    public PidRouteMapView(Context context, AttributeSet attrs) { super(context, attrs); }

    /** Each waypoint is an object like {"x": 12.3, "y": -4.5}. */
    public void setRoute(String name, JSONArray points) {
        routeName = name == null ? "" : name;
        int n = points == null ? 0 : points.length();
        xs = new float[n];
        ys = new float[n];
        for (int i = 0; i < n; i++) {
            JSONObject p = points.optJSONObject(i);
            if (p != null) {
                xs[i] = (float) p.optDouble("x", 0);
                ys[i] = (float) p.optDouble("y", 0);
            }
        }
        invalidate();
    }

    /** Hide the location when disconnected/stale; never draw an old pose as live. */
    public void setPose(JSONObject pose) {
        hasPose = pose != null && !pose.isNull("x") && !pose.isNull("y")
                && pose.has("x") && pose.has("y");
        if (hasPose) {
            poseX = (float) pose.optDouble("x", 0);
            poseY = (float) pose.optDouble("y", 0);
            poseYaw = (float) pose.optDouble("yaw_deg", 0);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(249, 251, 254));
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (xs.length < 2) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(NAVY);
            paint.setTextSize(sp(15));
            canvas.drawText("경로 데이터 대기 중", w * 0.5f, h * 0.5f - dp(8), paint);
            paint.setColor(MUTED);
            paint.setTextSize(sp(12));
            canvas.drawText("NUC Bluetooth 연결 후 경로를 수신합니다.",
                    w * 0.5f, h * 0.5f + dp(17), paint);
            return;
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        // Fit the route, not the chair: the map must not constantly zoom/pan
        // because a GICP pose briefly jumps far away during initialization.
        final float routeWidth = Math.max(5f, maxX - minX);
        final float routeHeight = Math.max(5f, maxY - minY);
        final float margin = dp(34);
        final float scale = Math.max(0.001f,
                Math.min((w - margin * 2) / routeWidth,
                         (h - margin * 2) / routeHeight));
        final float ox = (w - (minX + maxX) * scale) * 0.5f;
        final float oy = (h + (minY + maxY) * scale) * 0.5f;

        // Light 10 m reference grid, not a fabricated environmental map.
        paint.setColor(GRID);
        paint.setStrokeWidth(dp(0.7f));
        paint.setStyle(Paint.Style.STROKE);
        for (float x = (float) Math.ceil(minX / 10f) * 10f; x <= maxX; x += 10f) {
            float px = ox + x * scale;
            canvas.drawLine(px, margin / 2f, px, h - margin / 2f, paint);
        }
        for (float y = (float) Math.ceil(minY / 10f) * 10f; y <= maxY; y += 10f) {
            float py = oy - y * scale;
            canvas.drawLine(margin / 2f, py, w - margin / 2f, py, paint);
        }

        routePath.reset();
        routePath.moveTo(ox + xs[0] * scale, oy - ys[0] * scale);
        for (int i = 1; i < xs.length; i++) {
            routePath.lineTo(ox + xs[i] * scale, oy - ys[i] * scale);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(8));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE);
        canvas.drawPath(routePath, paint);
        paint.setStrokeWidth(dp(4));
        paint.setColor(BLUE);
        canvas.drawPath(routePath, paint);
        paint.setStyle(Paint.Style.FILL);

        // Start and destination stay visible even before localization is ready.
        marker(canvas, ox + xs[0] * scale, oy - ys[0] * scale, GREEN, "출발", w, h);
        marker(canvas, ox + xs[xs.length - 1] * scale,
                oy - ys[ys.length - 1] * scale, BLUE, "도착", w, h);

        if (hasPose) {
            float px = ox + poseX * scale, py = oy - poseY * scale;
            // Do not place an off-map chair over a misleading clipped edge.
            if (px >= 0 && px <= w && py >= 0 && py <= h) {
                paint.setColor(Color.WHITE);
                canvas.drawCircle(px, py, dp(11), paint);
                paint.setColor(GREEN);
                canvas.drawCircle(px, py, dp(7), paint);
                double heading = Math.toRadians(poseYaw);
                float ux = (float) Math.cos(heading);
                float uy = -(float) Math.sin(heading);
                float tx = px + ux * dp(24), ty = py + uy * dp(24);
                paint.setColor(GREEN);
                paint.setStrokeWidth(dp(3));
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(px, py, tx, ty, paint);
                arrowPath.reset();
                arrowPath.moveTo(tx, ty);
                float sideX = -uy, sideY = ux;
                arrowPath.lineTo(tx - ux * dp(11) + sideX * dp(6),
                        ty - uy * dp(11) + sideY * dp(6));
                arrowPath.lineTo(tx - ux * dp(11) - sideX * dp(6),
                        ty - uy * dp(11) - sideY * dp(6));
                arrowPath.close();
                canvas.drawPath(arrowPath, paint);
            }
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(NAVY);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(sp(13));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText(routeName.replace("_to_", " → "), dp(14), dp(22), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(sp(11));
        paint.setColor(hasPose ? GREEN : MUTED);
        canvas.drawText(hasPose ? "● 실시간 위치 / 방향" : "○ 현재 위치 수신 대기",
                dp(14), h - dp(12), paint);
    }

    private void marker(Canvas c, float x, float y, int color, String label,
                        float width, float height) {
        paint.setColor(Color.WHITE);
        c.drawCircle(x, y, dp(8), paint);
        paint.setColor(color);
        c.drawCircle(x, y, dp(5), paint);
        paint.setColor(NAVY);
        paint.setTextSize(sp(11));
        paint.setTextAlign(x < width * 0.75f ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float labelX = x < width * 0.75f ? x + dp(9) : x - dp(9);
        float labelY = Math.max(dp(36), Math.min(height - dp(28), y - dp(10)));
        c.drawText(label, labelX, labelY, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
