package com.example.jetsonbtmonitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * FF–PID operator UI: ADMIN READY + RIDER route/drive, with one common BT link.
 * All motion and Safety decisions remain on the ROS1 NUC.
 */
public final class PidOperatorActivity extends AppCompatActivity {
    private static final int REQ_BLUETOOTH = 910;
    private static final int INK = Color.rgb(27, 43, 62);
    private static final int MUTED = Color.rgb(96, 110, 126);
    private static final int BLUE = Color.rgb(10, 99, 184);
    private static final int GREEN = Color.rgb(19, 132, 86);
    private static final int AMBER = Color.rgb(161, 101, 0);
    private static final int RED = Color.rgb(191, 54, 42);
    private static final int BG = Color.rgb(244, 247, 251);
    private static final int WHITE = Color.WHITE;
    private static final String PREFS = "wheelchair_bt_prefs";
    private static final String MAC_KEY = "last_bt_device_mac";
    private static final String[] ROUTES = {
            "A_to_D4", "A_to_D5", "D4_to_A", "D4_to_D5", "D5_to_A", "D5_to_D4"
    };
    private static final String[] ROUTE_LABELS = {
            "A → D4", "A → D5", "D4 → A", "D4 → D5", "D5 → A", "D5 → D4"
    };
    private BluetoothWheelchairClient bluetooth;
    private LinearLayout root;
    private LinearLayout adminScreen, riderScreen;
    private TextView commonBluetooth, adminBluetooth, riderBluetooth;
    private TextView readyState, readyComponents, readyLogs, readyNote;
    private TextView driveNote, safetyState, safetyReason, currentRoute;
    private Spinner routeSpinner;
    private PidRouteMapView mapView;
    private Button readyStart, readyStop, driveStart, driveStop, connect;
    private boolean admin = true;
    private boolean connected = false;
    private long lastFrameMs = 0L;
    private String selectedRoute = "A_to_D5";
    private String currentDrivePhase = "IDLE";
    private boolean driveConfirmed = false;
    private boolean stopPending = false;
    private boolean routeSpinnerUpdating = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable freshnessTick = new Runnable() {
        @Override public void run() {
            if (connected && System.currentTimeMillis() - lastFrameMs > 3500) {
                showBluetooth("통신 지연 · 상태 확인 불가", AMBER);
                showUnknownSafety("상태 정보를 수신하지 못했습니다.");
                driveStart.setEnabled(false);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        bluetooth = BluetoothWheelchairClient.getSharedInstance(new BluetoothWheelchairClient.Callback() {
            @Override public void onConnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    connected = true;
                    lastFrameMs = System.currentTimeMillis();
                    String name = "NUC";
                    try { if (device != null && device.getName() != null) name = device.getName(); }
                    catch (SecurityException ignored) {}
                    showBluetooth("연결됨 · " + name, GREEN);
                    // Always re-check NUC state and route after a reconnect.
                    send("status", null);
                    send("route", null);
                    send("map", null);
                });
            }
            @Override public void onDisconnected() {
                runOnUiThread(() -> {
                    connected = false;
                    driveConfirmed = false;
                    showBluetooth("연결 끊김 · 주행 상태 확인 불가", RED);
                    driveStart.setEnabled(false);
                    readyStart.setEnabled(false);
                    showUnknownSafety("Bluetooth 연결이 끊어졌습니다. 조이스틱으로 안전을 확보하세요.");
                });
            }
            @Override public void onReconnecting(int attempt, long delayMs) {
                runOnUiThread(() -> showBluetooth("재연결 중 · " + attempt + "회", AMBER));
            }
            @Override public void onLineReceived(String line) {
                runOnUiThread(() -> receive(line));
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    showBluetooth("연결 오류", RED);
                    readyNote.setText(message);
                });
            }
        });
        if (bluetooth.isConnected()) {
            connected = true;
            lastFrameMs = System.currentTimeMillis();
            showBluetooth("연결됨", GREEN);
            send("status", null);
            send("route", null);
            send("map", null);
        }
        ui.postDelayed(freshnessTick, 1000);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(background(WHITE, Color.rgb(224, 232, 239)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(9), 0, dp(9));
        parent.addView(c, lp);
        return c;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        parent.addView(child, p);
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setMinHeight(dp(50));
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = column();
        root.setPadding(dp(18), dp(18), dp(18), dp(26));
        scroll.addView(root);
        setContentView(scroll);

        add(root, text("UNICON  |  FF–PID", 24, INK, true), 4);
        commonBluetooth = text("Bluetooth 연결 안 됨", 13, RED, true);
        add(root, commonBluetooth, 8);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button adminTab = button("관리자", BLUE);
        Button riderTab = button("탑승자", MUTED);
        tabs.addView(adminTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(52), 1);
        rp.leftMargin = dp(8);
        tabs.addView(riderTab, rp);
        add(root, tabs, 18);

        adminScreen = column();
        riderScreen = column();
        add(root, adminScreen, 5);
        add(root, riderScreen, 5);
        riderScreen.setVisibility(View.GONE);
        adminTab.setOnClickListener(v -> {
            admin = true;
            adminScreen.setVisibility(View.VISIBLE);
            riderScreen.setVisibility(View.GONE);
            adminTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
            riderTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MUTED));
        });
        riderTab.setOnClickListener(v -> {
            admin = false;
            adminScreen.setVisibility(View.GONE);
            riderScreen.setVisibility(View.VISIBLE);
            riderTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
            adminTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MUTED));
        });

        LinearLayout bcard = card(adminScreen);
        add(bcard, text("Bluetooth 연결", 18, INK, true), 0);
        adminBluetooth = text("연결 안 됨", 14, RED, false);
        add(bcard, adminBluetooth, 7);
        connect = button("NUC 연결", BLUE);
        add(bcard, connect, 12);
        connect.setOnClickListener(v -> connectNuc());

        LinearLayout rcard = card(adminScreen);
        add(rcard, text("READY 시스템", 18, INK, true), 0);
        LinearLayout readyBtns = new LinearLayout(this);
        readyStart = button("READY 시작", GREEN);
        readyStop = button("READY 종료", RED);
        readyBtns.addView(readyStart, new LinearLayout.LayoutParams(0, dp(55), 1));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(55), 1);
        stopLp.leftMargin = dp(8);
        readyBtns.addView(readyStop, stopLp);
        add(rcard, readyBtns, 14);
        readyState = text("READY: 상태 미확인", 16, MUTED, true);
        add(rcard, readyState, 17);
        readyComponents = text("LiDAR, IMU, FAST-LIO, GICP 등의 상태를 수신하면 표시합니다.", 14, MUTED, false);
        readyComponents.setLineSpacing(dp(4), 1.0f);
        add(rcard, readyComponents, 10);
        readyNote = text("진행 단계 및 실패 사유", 13, MUTED, false);
        add(rcard, readyNote, 10);
        readyLogs = text("", 12, MUTED, false);
        readyLogs.setLineSpacing(dp(3), 1f);
        add(rcard, readyLogs, 8);
        readyStart.setOnClickListener(v -> send("ready_start", null));
        readyStop.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("READY 종료")
                .setMessage("주행 중이면 먼저 정지를 요청하고 확인한 뒤 READY 시스템을 종료합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("종료", (dialog, which) -> send("ready_stop", null))
                .show());

        LinearLayout riderConnectionCard = card(riderScreen);
        add(riderConnectionCard, text("Bluetooth", 17, INK, true), 0);
        riderBluetooth = text("연결 안 됨", 14, RED, false);
        add(riderConnectionCard, riderBluetooth, 6);

        LinearLayout routeCard = card(riderScreen);
        add(routeCard, text("경로 선택", 18, INK, true), 0);
        routeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, ROUTE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSpinner.setAdapter(adapter);
        routeSpinner.setSelection(1);
        add(routeCard, routeSpinner, 10);
        currentRoute = text("선택 경로: A → D5", 13, MUTED, false);
        add(routeCard, currentRoute, 6);
        routeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (routeSpinnerUpdating) return;
                selectedRoute = ROUTES[position];
                currentRoute.setText("선택 경로: " + ROUTE_LABELS[position]);
                mapView.setRoute(selectedRoute, null);
                if (connected) send("route_select", selectedRoute);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        LinearLayout mapCard = card(riderScreen);
        add(mapCard, text("지도 · 선택 경로 · 현재 위치", 17, INK, true), 0);
        mapView = new PidRouteMapView(this);
        mapView.setBackground(background(BG, Color.rgb(220, 230, 240)));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-1, dp(330));
        mapLp.topMargin = dp(12);
        mapCard.addView(mapView, mapLp);
        add(mapCard, text("위치 화살표는 ROS map 좌표계 기준입니다.", 12, MUTED, false), 8);

        LinearLayout controls = card(riderScreen);
        add(controls, text("주행 제어", 18, INK, true), 0);
        LinearLayout driveButtons = new LinearLayout(this);
        driveStart = button("주행 시작", GREEN);
        driveStop = button("주행 종료", RED);
        driveButtons.addView(driveStart, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams dsp = new LinearLayout.LayoutParams(0, dp(58), 1);
        dsp.leftMargin = dp(8);
        driveButtons.addView(driveStop, dsp);
        add(controls, driveButtons, 12);
        driveStart.setEnabled(false);
        driveStop.setEnabled(false);
        driveStart.setOnClickListener(v -> {
            driveNote.setText("출발 조건을 확인하고 있습니다.");
            currentDrivePhase = "CHECKING";
            updateControls();
            send("drive_start", selectedRoute);
        });
        driveStop.setOnClickListener(v -> {
            // STOP does not wait for the long-running drive shell.
            driveNote.setText("주행 종료 요청 중 · MANUAL 정차 확인 대기");
            stopPending = true;
            currentDrivePhase = "STOPPING";
            updateControls();
            send("drive_stop", null);
        });

        LinearLayout statusCard = card(riderScreen);
        add(statusCard, text("주행 상태 및 안내", 18, INK, true), 0);
        driveNote = text("경로를 선택하고 주행 시작을 누르세요.", 14, INK, false);
        driveNote.setLineSpacing(dp(5), 1f);
        add(statusCard, driveNote, 11);
        safetyState = text("Safety: 대기", 23, MUTED, true);
        add(statusCard, safetyState, 14);
        safetyReason = text("주행 시작 전에는 Safety 상태를 표시하지 않습니다.", 14, MUTED, false);
        add(statusCard, safetyReason, 7);
        updateControls();
    }

    private void showBluetooth(String message, int color) {
        commonBluetooth.setText("Bluetooth  ·  " + message);
        commonBluetooth.setTextColor(color);
        adminBluetooth.setText(message);
        adminBluetooth.setTextColor(color);
        riderBluetooth.setText(message);
        riderBluetooth.setTextColor(color);
        updateControls();
    }

    private void updateControls() {
        if (readyStart == null) return;
        boolean link = connected && System.currentTimeMillis() - lastFrameMs < 3500;
        readyStart.setEnabled(link);
        readyStop.setEnabled(link);
        connect.setEnabled(!connected);
        boolean active = Arrays.asList("CHECKING", "START_REQUEST_ACCEPTED",
                "RUNNING", "STOPPING", "STATUS_STALE").contains(currentDrivePhase);
        driveStart.setEnabled(link && !active);
        driveStop.setEnabled(link && active && !"STOPPING".equals(currentDrivePhase));
    }

    private void connectNuc() {
        if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            readyNote.setText("스마트폰 Bluetooth를 켜고 NUC와 먼저 페어링하세요.");
            return;
        }
        try {
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (paired == null || paired.isEmpty()) {
                readyNote.setText("페어링된 기기가 없습니다. 스마트폰 설정에서 NUC와 페어링하세요.");
                return;
            }
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String lastMac = prefs.getString(MAC_KEY, "");
            BluetoothDevice target = null;
            if (!lastMac.isEmpty()) {
                for (BluetoothDevice d : paired) {
                    if (lastMac.equals(d.getAddress())) { target = d; break; }
                }
            }
            if (target == null) {
                List<BluetoothDevice> nucs = new ArrayList<>();
                for (BluetoothDevice d : paired) {
                    String n = d.getName();
                    if (n != null && (n.toLowerCase().contains("nuc") ||
                            n.toLowerCase().contains("wheelchair"))) nucs.add(d);
                }
                if (nucs.size() == 1) target = nucs.get(0);
            }
            if (target == null) {
                readyNote.setText("NUC를 자동 식별할 수 없습니다. 스마트폰 Bluetooth 설정에서 NUC를 페어링하세요. "
                        + "여러 후보가 있으면 기존 앱에서 저장한 NUC 주소가 필요합니다.");
                return;
            }
            String address = target.getAddress();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(MAC_KEY, address).apply();
            showBluetooth("연결 시도 중", AMBER);
            bluetooth.connect(target);
        } catch (SecurityException ex) {
            readyNote.setText("Bluetooth 접근 권한을 허용해야 합니다.");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == REQ_BLUETOOTH && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            connectNuc();
        }
    }

    private void send(String command, String route) {
        if (bluetooth == null || !bluetooth.isConnected()) {
            readyNote.setText("NUC Bluetooth 연결이 필요합니다.");
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("command", command);
            if (route != null) obj.put("route", route);
            bluetooth.sendJsonCommand(obj);
        } catch (JSONException ignored) {}
    }

    private void receive(String line) {
        try {
            JSONObject f = new JSONObject(line);
            String type = f.optString("type");
            lastFrameMs = System.currentTimeMillis();
            if (!connected) connected = true;
            showBluetooth("연결됨 · 상태 수신 중", GREEN);
            if ("route".equals(type)) {
                if (selectedRoute.equals(f.optString("route"))) {
                    mapView.setRoute(selectedRoute, f.optJSONArray("points"));
                }
            } else if ("map".equals(type)) {
                mapView.setMap(f);
            } else if ("telemetry".equals(type)) {
                renderTelemetry(f);
            } else if ("drive_preflight".equals(type)) {
                if (!f.optBoolean("ok", false)) {
                    renderPreflight(f);
                } else {
                    driveNote.setText("출발 위치 확인 완료 · 제어기 실행 확인 중");
                }
            } else if ("drive_result".equals(type)) {
                String state = f.optString("status");
                if ("REJECTED".equals(state)) {
                    currentDrivePhase = "REJECTED";
                    driveConfirmed = false;
                    JSONObject pf = f.optJSONObject("preflight");
                    if (pf != null && !pf.optBoolean("ok", true)) renderPreflight(pf);
                    else driveNote.setText("출발 거부: " + f.optString("message", f.optString("reason")));
                    showUnknownSafety("출발하지 않았습니다.");
                } else if ("STARTED".equals(state) && "ROS_RUNNING".equals(f.optString("reason"))) {
                    currentDrivePhase = "RUNNING";
                    driveConfirmed = true;
                    driveNote.setText("주행 중 · Safety 상태를 확인하세요.");
                } else if ("STOPPED".equals(state)) {
                    currentDrivePhase = "STOPPED";
                    stopPending = false;
                    driveConfirmed = false;
                    driveNote.setText("주행 종료 완료 · READY는 유지됩니다.");
                    showUnknownSafety("주행이 종료되었습니다.");
                } else if ("STOP_UNVERIFIED".equals(state)) {
                    currentDrivePhase = "STATUS_STALE";
                    driveNote.setText("정지 확인 실패 · 조이스틱으로 안전을 확보하세요.");
                    showUnknownSafety("정지를 확인할 수 없습니다.");
                }
            } else if ("job_result".equals(type)) {
                String name = f.optString("job");
                String message = (f.optBoolean("ok") ? "완료: " : "실패: ") + name;
                readyNote.setText(message + "\n" + f.optString("detail", ""));
                if ("drive_stop".equals(name) && !f.optBoolean("ok")) {
                    currentDrivePhase = "STATUS_STALE";
                    showUnknownSafety("주행 종료가 확인되지 않았습니다.");
                }
            } else if ("ack".equals(type)) {
                if (!f.optBoolean("ok", false)) {
                    String cmd = f.optString("command");
                    if ("drive_start".equals(cmd)) currentDrivePhase = "REJECTED";
                    if ("drive_stop".equals(cmd)) currentDrivePhase = "STATUS_STALE";
                    readyNote.setText("명령 거부: " + f.optString("detail"));
                    driveNote.setText("명령 거부: " + f.optString("detail"));
                }
            }
            updateControls();
        } catch (Exception ex) {
            readyNote.setText("NUC 메시지 처리 오류: " + ex.getMessage());
        }
    }

    private void renderPreflight(JSONObject f) {
        StringBuilder b = new StringBuilder();
        b.append(f.optString("message", "출발 위치를 조정해주세요."));
        JSONArray guides = f.optJSONArray("guidance");
        if (guides != null) for (int i = 0; i < guides.length(); i++) {
            b.append("\n").append(i + 1).append(". ").append(guides.optString(i));
        }
        driveNote.setText(b.toString());
        currentDrivePhase = "REJECTED";
    }

    private void renderTelemetry(JSONObject f) {
        JSONObject ready = f.optJSONObject("ready");
        if (ready != null) {
            String state = ready.optString("state", "UNKNOWN");
            readyState.setText("READY: " + state);
            readyState.setTextColor("READY".equals(state) ? GREEN :
                    "FAILED".equals(state) ? RED : MUTED);
            JSONObject components = ready.optJSONObject("components");
            if (components != null) {
                StringBuilder details = new StringBuilder();
                String[] ordered = {"LiDAR", "IMU", "FAST-LIO", "Map Pose",
                        "GICP Diagnostics", "Perception", "GICP Exclusions",
                        "Wheel Status", "UART Diagnostics"};
                for (String key : ordered) {
                    details.append(key).append(": ").append(components.optString(key, "UNKNOWN"))
                            .append("\n");
                }
                readyComponents.setText(details.toString().trim());
            }
            JSONObject jobs = ready.optJSONObject("jobs");
            if (jobs != null) {
                StringBuilder log = new StringBuilder();
                for (String job : new String[]{"ready_start", "ready_status", "ready_stop"}) {
                    JSONObject info = jobs.optJSONObject(job);
                    if (info != null) {
                        log.append(job).append(" · ").append(info.optString("state"))
                                .append("\n");
                        JSONArray tail = info.optJSONArray("tail");
                        if (tail != null && tail.length() > 0) {
                            log.append(tail.optString(tail.length() - 1)).append("\n");
                        }
                    }
                }
                readyLogs.setText(log.toString().trim());
            }
        }
        String route = f.optString("selected_route", "");
        if (!route.isEmpty() && Arrays.asList(ROUTES).contains(route) &&
                !route.equals(selectedRoute)) {
            selectedRoute = route;
            routeSpinnerUpdating = true;
            routeSpinner.setSelection(Arrays.asList(ROUTES).indexOf(route));
            routeSpinnerUpdating = false;
            currentRoute.setText("선택 경로: " + route.replace("_to_", " → "));
            send("route", null);
        }
        JSONObject pose = f.optJSONObject("pose");
        mapView.setPose(pose);
        String phase = f.optString("drive_phase", "UNKNOWN");
        currentDrivePhase = phase;
        driveConfirmed = f.optBoolean("drive_confirmed", false);
        JSONObject safety = f.optJSONObject("safety");
        if (driveConfirmed && ("RUNNING".equals(phase) || "STATUS_STALE".equals(phase))) {
            if (safety == null || "STATUS_STALE".equals(phase)) {
                showUnknownSafety("최신 Safety 데이터를 확인할 수 없습니다.");
            } else {
                String state = safety.optString("state", "UNKNOWN");
                safetyState.setText("Safety: " + state);
                safetyState.setTextColor("CLEAR".equals(state) ? GREEN :
                        "SLOW".equals(state) ? AMBER : "STOP".equals(state) ? RED : MUTED);
                safetyReason.setText(safety.optString("reason", "이유 정보 없음"));
            }
        } else if (!driveConfirmed && !"CHECKING".equals(phase) &&
                !"START_REQUEST_ACCEPTED".equals(phase) && !"REJECTED".equals(phase)) {
            showUnknownSafety("주행 중이 아닙니다.");
        }
        if (stopPending && "STOPPED".equals(phase)) stopPending = false;
        updateControls();
    }

    private void showUnknownSafety(String why) {
        safetyState.setText("Safety: 확인 불가");
        safetyState.setTextColor(MUTED);
        safetyReason.setText(why);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(freshnessTick);
        if (bluetooth != null) bluetooth.setCallback(null);
        super.onDestroy();
    }
}
