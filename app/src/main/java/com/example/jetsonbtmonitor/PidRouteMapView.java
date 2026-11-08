package com.example.jetsonbtmonitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.InflaterInputStream;

/** Displays the NUC's current drivable-mask map, selected map-frame route, and chair centre. */
public final class PidRouteMapView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();
    private final RectF bitmapDst = new RectF();
    private Bitmap mask;
    private float maskX, maskY, maskResolution;
    private float[] xs = new float[0], ys = new float[0];
    private boolean hasPose;
    private float poseX, poseY, poseYaw;
    private boolean poseFresh;
    private String routeName = "";
    private final int navy = Color.rgb(25, 46, 64);
    private final int routeColor = Color.rgb(0, 111, 209);
    private final int chairColor = Color.rgb(19, 142, 98);

    public PidRouteMapView(Context context) { super(context); }
    public PidRouteMapView(Context context, AttributeSet attrs) { super(context, attrs); }

    /** Waypoints are JSON OBJECTS {x,y}, not the old bridge's [x,y] arrays. */
    public void setRoute(String name, JSONArray points) {
        routeName = name;
        if (points == null || points.length() == 0) {
            xs = new float[0];
            ys = new float[0];
        } else {
            int n = points.length();
            xs = new float[n];
            ys = new float[n];
            for (int i = 0; i < n; i++) {
                JSONObject p = points.optJSONObject(i);
                xs[i] = p == null ? 0f : (float) p.optDouble("x", 0);
                ys[i] = p == null ? 0f : (float) p.optDouble("y", 0);
            }
        }
        invalidate();
    }

    /** The map is PGM bytes downsampled on NUC and zlib/base64 encoded. */
    public void setMap(JSONObject json) throws Exception {
        if (!"zlib-gray8".equals(json.optString("encoding"))) {
            throw new IllegalArgumentException("Unsupported map encoding");
        }
        final int width = json.getInt("width"), height = json.getInt("height");
        if (width < 1 || height < 1 || width > 1000 || height > 1000) {
            throw new IllegalArgumentException("Invalid map dimensions");
        }
        byte[] packed = Base64.decode(json.getString("pixels"), Base64.DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height);
        try (InflaterInputStream inflater = new InflaterInputStream(
                new ByteArrayInputStream(packed))) {
            byte[] part = new byte[8192];
            int count;
            while ((count = inflater.read(part)) != -1) {
                if (out.size() + count > width * height) {
                    throw new IllegalArgumentException("Oversized map pixels");
                }
                out.write(part, 0, count);
            }
        }
        byte[] pixels = out.toByteArray();
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Map size mismatch");
        }
        int[] colors = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int v = pixels[i] & 0xff;
            // Show free/drivable corridor light, mask boundaries dark.
            colors[i] = Color.rgb(v, v, v);
        }
        Bitmap newMask = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        Bitmap old = mask;
        mask = newMask;
        maskX = (float) json.getDouble("origin_x");
        maskY = (float) json.getDouble("origin_y");
        maskResolution = (float) json.getDouble("resolution");
        if (old != null) old.recycle();
        invalidate();
    }

    public void setPose(JSONObject pose) {
        if (pose == null || !pose.has("x") || !pose.has("y")) {
            hasPose = false;
            poseFresh = false;
        } else {
            hasPose = true;
            poseFresh = true;
            poseX = (float) pose.optDouble("x", 0);
            poseY = (float) pose.optDouble("y", 0);
            poseYaw = (float) pose.optDouble("yaw_deg", 0);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawColor(Color.rgb(245, 248, 251));
        if (w <= 0 || h <= 0) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        if (mask != null && maskResolution > 0) {
            minX = Math.min(minX, maskX);
            minY = Math.min(minY, maskY);
            maxX = Math.max(maxX, maskX + mask.getWidth() * maskResolution);
            maxY = Math.max(maxY, maskY + mask.getHeight() * maskResolution);
        }
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        if (hasPose && minX != Float.MAX_VALUE) {
            minX = Math.min(minX, poseX); maxX = Math.max(maxX, poseX);
            minY = Math.min(minY, poseY); maxY = Math.max(maxY, poseY);
        }
        if (minX == Float.MAX_VALUE) {
            paint.setColor(Color.GRAY); paint.setTextSize(36f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("지도를 수신하지 않았습니다", w / 2, h / 2, paint);
            return;
        }
        float spanX = Math.max(2f, maxX - minX);
        float spanY = Math.max(2f, maxY - minY);
        float margin = Math.min(34f, Math.min(w, h) * 0.07f);
        float scale = Math.min((w - margin * 2) / spanX, (h - margin * 2) / spanY);
        float ox = (w - spanX * scale) / 2f - minX * scale;
        float oy = (h - spanY * scale) / 2f + maxY * scale;

        paint.setColor(Color.rgb(225, 232, 239));
        paint.setStrokeWidth(1);
        float gridStep = 10f;
        for (float gx = (float) Math.ceil(minX / gridStep) * gridStep; gx <= maxX; gx += gridStep) {
            float xx = ox + gx * scale;
            canvas.drawLine(xx, margin, xx, h - margin, paint);
        }
        for (float gy = (float) Math.ceil(minY / gridStep) * gridStep; gy <= maxY; gy += gridStep) {
            float yy = oy - gy * scale;
            canvas.drawLine(margin, yy, w - margin, yy, paint);
        }
        if (mask != null && maskResolution > 0) {
            // World Y increases upwards; Android canvas Y increases downwards.
            bitmapDst.set(ox + maskX * scale,
                    oy - (maskY + mask.getHeight() * maskResolution) * scale,
                    ox + (maskX + mask.getWidth() * maskResolution) * scale,
                    oy - maskY * scale);
            paint.setAlpha(170);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(mask, null, bitmapDst, paint);
            paint.setAlpha(255);
        }
        if (xs.length > 1) {
            routePath.reset();
            routePath.moveTo(ox + xs[0] * scale, oy - ys[0] * scale);
            for (int i = 1; i < xs.length; i++) {
                routePath.lineTo(ox + xs[i] * scale, oy - ys[i] * scale);
            }
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(7f); paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(routePath, paint);
            paint.setColor(routeColor); paint.setStrokeWidth(4.5f);
            canvas.drawPath(routePath, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (hasPose) {
            float x = ox + poseX * scale, y = oy - poseY * scale;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, 13, paint);
            paint.setColor(poseFresh ? chairColor : Color.GRAY);
            canvas.drawCircle(x, y, 9, paint);
            double a = Math.toRadians(poseYaw);
            float dx = (float) Math.cos(a), dy = -(float) Math.sin(a);
            paint.setStrokeWidth(5);
            canvas.drawLine(x, y, x + dx * 24, y + dy * 24, paint);
        }
        paint.setColor(navy);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(25);
        canvas.drawText(routeName.replace("_to_", " → "), 14, 32, paint);
    }
}
