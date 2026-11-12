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
import java.util.Locale;

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
            "애지문(2번 출구) → 정몽구센터", "애지문(2번 출구) → 2공학관",
            "정몽구센터 → 애지문(2번 출구)", "정몽구센터 → 2공학관",
            "2공학관 → 애지문(2번 출구)", "2공학관 → 정몽구센터"
    };
    private BluetoothWheelchairClient bluetooth;
    private LinearLayout root;
    private LinearLayout adminScreen, riderScreen;
    private TextView commonBluetooth, adminBluetooth, riderBluetooth;
    private TextView readyState, readyLogs, readyNote, readyButtonHint;
    private LinearLayout readyComponentRows;
    private final TextView[] readyComponentValues = new TextView[9];
    private static final String[] COMPONENT_KEYS = {
            "LiDAR", "IMU", "FAST-LIO", "Map Pose", "GICP Diagnostics",
            "Perception", "GICP Exclusions", "Wheel Status", "UART Diagnostics"
    };
    private static final String[] COMPONENT_LABELS = {
            "LiDAR", "IMU", "FAST-LIO", "지도 위치추정", "GICP 진단",
            "장애물 인식", "GICP 제외 영역", "바퀴 상태", "UART 진단"
    };
    private TextView lidarTestStatus, lidarTestDetail;
    private final TextView[] lidarTestValues = new TextView[4];
    private Button lidarTestStart, lidarTestStop;
    private String lidarTestPhase = "STOPPED";
    private boolean lidarTestWasRunning = false;
    private String lidarTestLastError = null;
    private String readyPhase = "UNKNOWN";
    private TextView driveNote, safetyState, safetyReason, currentRoute, wheelModeText;
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
    private String wheelMode = "UNKNOWN";
    private boolean routeSpinnerUpdating = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable freshnessTick = new Runnable() {
        @Override public void run() {
            if (connected && System.currentTimeMillis() - lastFrameMs > 3500) {
                showBluetooth("통신 지연 · 상태 확인 불가", AMBER);
                showUnknownSafety("상태 정보를 수신하지 못했습니다.");
                mapView.setPose(null);
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
                    mapView.setPose(null);
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
        readyComponentRows = column();
        add(rcard, readyComponentRows, 10);
        for (int i = 0; i < COMPONENT_KEYS.length; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(10), dp(8), dp(10), dp(8));
            line.setBackground(background(BG, 0));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, -2);
            lineParams.topMargin = dp(4);
            readyComponentRows.addView(line, lineParams);
            TextView nameView = text(COMPONENT_LABELS[i], 13, INK, true);
            line.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));
            readyComponentValues[i] = text("미확인", 13, MUTED, true);
            line.addView(readyComponentValues[i]);
        }
        readyButtonHint = text("READY를 시작하면 각 구성요소의 수신 상태가 표시됩니다.", 12, MUTED, false);
        add(rcard, readyButtonHint, 9);
        readyNote = text("진행 단계 및 실패 사유", 13, MUTED, false);
        add(rcard, readyNote, 10);
        add(rcard, text("READY 실행 기록", 14, INK, true), 12);
        readyLogs = text("", 12, MUTED, false);
        readyLogs.setLineSpacing(dp(3), 1f);
        add(rcard, readyLogs, 8);
        readyStart.setOnClickListener(v -> {
            readyPhase = "STARTING";
            readyNote.setText("READY 실행 중 · 각 단계 상태를 확인하고 있습니다.");
            readyLogs.setText("");
            updateControls();
            send("ready_start", null);
        });
        LinearLayout lidarCard = card(adminScreen);
        add(lidarCard, text("LiDAR 연결 테스트", 18, INK, true), 0);
        add(lidarCard, text("기존 READY를 안전하게 종료한 후 테스트 전용 센서를 실행합니다. 테스트 종료 시 전체 테스트를 정리합니다.", 13, MUTED, false), 7);
        lidarTestStatus = text("테스트 미실행", 17, MUTED, true);
        add(lidarCard, lidarTestStatus, 11);
        String[] sensorNames = {"ROS Master", "Livox 드라이버", "LiDAR", "IMU"};
        for (int i = 0; i < sensorNames.length; i++) {
            LinearLayout sensorLine = new LinearLayout(this);
            sensorLine.setOrientation(LinearLayout.HORIZONTAL);
            sensorLine.setPadding(dp(9), dp(9), dp(9), dp(9));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(4);
            lidarCard.addView(sensorLine, rowParams);
            sensorLine.setBackground(background(BG, 0));
            TextView name = text(sensorNames[i], 14, INK, true);
            sensorLine.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            lidarTestValues[i] = text("미실행", 14, MUTED, true);
            sensorLine.addView(lidarTestValues[i]);
        }
        lidarTestDetail = text("시작 버튼을 누르면 기존 READY를 정리하고 독립 테스트를 시작합니다.", 13, MUTED, false);
        lidarTestDetail.setLineSpacing(dp(4), 1f);
        add(lidarCard, lidarTestDetail, 9);
        LinearLayout lidarButtons = new LinearLayout(this);
        lidarTestStart = button("테스트 시작", BLUE);
        lidarTestStop = button("테스트 종료", RED);
        lidarButtons.addView(lidarTestStart, new LinearLayout.LayoutParams(0, dp(55), 1));
        LinearLayout.LayoutParams ltStop = new LinearLayout.LayoutParams(0, dp(55), 1);
        ltStop.leftMargin = dp(8);
        lidarButtons.addView(lidarTestStop, ltStop);
        add(lidarCard, lidarButtons, 12);
        lidarTestStart.setOnClickListener(v -> {
            lidarTestLastError = null;
            lidarTestPhase = "STARTING";
            lidarTestStatus.setText("테스트 준비 중");
            updateControls();
            send("lidar_test_start", null);
        });
        lidarTestStop.setOnClickListener(v -> {
            lidarTestStatus.setText("테스트 종료 요청 중");
            send("lidar_test_stop", null);
        });
        readyStop.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("READY 종료")
                .setMessage("주행 중이면 먼저 정지를 요청하고 확인한 뒤 READY 시스템을 종료합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("종료", (dialog, which) -> {
                    readyPhase = "STOPPING";
                    readyNote.setText("READY 종료 요청 중 · 시스템 정리 상태를 확인하고 있습니다.");
                    updateControls();
                    send("ready_stop", null);
                })
                .show());

        LinearLayout riderConnectionCard = card(riderScreen);
        add(riderConnectionCard, text("Bluetooth", 17, INK, true), 0);
        riderBluetooth = text("연결 안 됨", 14, RED, false);
        add(riderConnectionCard, riderBluetooth, 6);

        LinearLayout routeCard = card(riderScreen);
        add(routeCard, text("경로 선택", 18, INK, true), 0);
        routeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, ROUTE_LABELS) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(INK);
                    ((TextView) view).setTextSize(16f);
                }
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(INK);
                    view.setBackgroundColor(WHITE);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSpinner.setAdapter(adapter);
        routeSpinner.setSelection(1);
        routeSpinner.setMinimumHeight(dp(52));
        add(routeCard, routeSpinner, 10);
        currentRoute = text("선택 경로: " + ROUTE_LABELS[1], 13, MUTED, false);
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
        add(mapCard, text("경로 · 현재 위치 및 방향", 17, INK, true), 0);
        mapView = new PidRouteMapView(this);
        mapView.setBackground(background(BG, Color.rgb(220, 230, 240)));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-1, dp(330));
        mapLp.topMargin = dp(12);
        mapCard.addView(mapView, mapLp);

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
        wheelModeText = text("현재 모드: 확인 불가", 16, MUTED, true);
        add(statusCard, wheelModeText, 10);
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
        boolean testActive = !"STOPPED".equals(lidarTestPhase);
        boolean readyBusy = "STARTING".equals(readyPhase) || "STOPPING".equals(readyPhase);
        boolean readyAlreadyActive = "READY".equals(readyPhase);
        readyStart.setEnabled(link && !testActive && !readyBusy && !readyAlreadyActive);
        readyStop.setEnabled(link && !testActive && !readyBusy && readyAlreadyActive);
        readyButtonHint.setText(!link ? "Bluetooth 상태 수신이 필요합니다." :
                testActive ? "LiDAR 테스트를 종료한 뒤 READY를 사용할 수 있습니다." :
                "STARTING".equals(readyPhase) ? "READY 시작 중에는 종료할 수 없습니다." :
                "STOPPING".equals(readyPhase) ? "READY 종료 작업이 진행 중입니다." :
                readyAlreadyActive ? "READY 실행 중 · 종료 버튼을 사용할 수 있습니다." :
                "READY를 시작하면 센서와 위치추정 상태를 검사합니다.");
        if (lidarTestStart != null) {
            lidarTestStart.setEnabled(link && !testActive &&
                    !"STARTING".equals(readyPhase) && !"STOPPING".equals(readyPhase));
            lidarTestStop.setEnabled(link && testActive &&
                    !"STARTING".equals(lidarTestPhase) && !"STOPPING".equals(lidarTestPhase));
        }
        connect.setEnabled(!connected);
        boolean active = Arrays.asList("CHECKING", "START_REQUEST_ACCEPTED",
                "RUNNING", "STOPPING", "STATUS_STALE", "GOAL_ARRIVED",
                "NEEDS_STOP", "STOP_UNVERIFIED").contains(currentDrivePhase);
        boolean startReady = "READY".equals(readyPhase) && "MANUAL".equals(wheelMode);
        driveStart.setEnabled(link && !active && !testActive && startReady);
        // At the goal, or after manual intervention, explicit STOP must still be possible.
        driveStop.setEnabled(link && !testActive &&
                Arrays.asList("CHECKING", "START_REQUEST_ACCEPTED", "RUNNING",
                        "STATUS_STALE", "GOAL_ARRIVED", "NEEDS_STOP",
                        "STOP_UNVERIFIED").contains(currentDrivePhase));
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
                            n.toLowerCase().contains("wheelchair") ||
                            n.equalsIgnoreCase("mprp3"))) nucs.add(d);
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
                    else driveNote.setText("출발 거부: " + koreanIssue(f.optString("message", f.optString("reason"))));
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
                    currentDrivePhase = "STOP_UNVERIFIED";
                    driveNote.setText("정지 확인 실패 · 조이스틱으로 안전을 확보하고 정지 상태를 재확인하세요.");
                    showUnknownSafety("정지를 확인할 수 없습니다.");
                }
            } else if ("job_result".equals(type)) {
                String name = f.optString("job");
                String message = (f.optBoolean("ok") ? "완료: " : "실패: ") + koreanJob(name);
                if ("lidar_test_start".equals(name)) {
                    if (!f.optBoolean("ok", false)) {
                        lidarTestPhase = "STOPPED";
                        lidarTestLastError = f.optString("detail", "실행 환경을 확인하세요.");
                        lidarTestStatus.setText("테스트 시작 실패");
                        lidarTestStatus.setTextColor(RED);
                        lidarTestDetail.setText(koreanIssue(lidarTestLastError));
                    }
                } else if ("lidar_test_stop".equals(name)) {
                    if (!f.optBoolean("ok", false)) {
                        lidarTestLastError = f.optString("detail", "프로세스 상태 확인 필요");
                        lidarTestStatus.setText("테스트 종료 실패 · 프로세스 확인 필요");
                        lidarTestStatus.setTextColor(RED);
                        lidarTestDetail.setText(koreanIssue(lidarTestLastError));
                    }
                } else if ("drive".equals(name) && !f.optBoolean("ok", false)) {
                    if (!"STOP_UNVERIFIED".equals(currentDrivePhase) &&
                            !"GOAL_ARRIVED".equals(currentDrivePhase) &&
                            !"STOPPED".equals(currentDrivePhase) &&
                            !"REJECTED".equals(currentDrivePhase)) {
                        currentDrivePhase = "REJECTED";
                        driveNote.setText("주행 시작 실패 · " + koreanIssue(f.optString("detail", "")));
                    }
                } else if ("ready_start".equals(name)) {
                    if (f.optBoolean("ok", false)) {
                        readyNote.setText("READY 완료 · 모든 필수 검사 통과");
                        readyNote.setTextColor(GREEN);
                    } else {
                        readyNote.setText("READY 실행 실패 · 아래 실패 원인을 확인하세요.");
                        readyNote.setTextColor(RED);
                    }
                } else if ("ready_stop".equals(name)) {
                    readyNote.setText((f.optBoolean("ok") ? "READY 종료 완료" : "READY 종료 실패")
                            + "\n" + koreanIssue(f.optString("detail", "")));
                }
                if ("drive_stop".equals(name) && !f.optBoolean("ok")) {
                    currentDrivePhase = "STOP_UNVERIFIED";
                    showUnknownSafety("주행 종료가 확인되지 않았습니다.");
                }
            } else if ("ack".equals(type)) {
                if (!f.optBoolean("ok", false)) {
                    String cmd = f.optString("command");
                    if ("drive_start".equals(cmd)) currentDrivePhase = "REJECTED";
                    if ("ready_start".equals(cmd)) readyPhase = "FAILED";
                    if ("ready_stop".equals(cmd)) readyPhase = "FAILED";
                    if ("drive_stop".equals(cmd)) currentDrivePhase = "STOP_UNVERIFIED";
                    String detail = f.optString("detail");
                    if (cmd.startsWith("lidar_test_")) {
                        lidarTestLastError = detail;
                        if ("lidar_test_start".equals(cmd)) lidarTestPhase = "STOPPED";
                        lidarTestStatus.setText("테스트 명령 거부");
                        lidarTestStatus.setTextColor(RED);
                        lidarTestDetail.setText(koreanIssue(detail));
                    } else if (cmd.startsWith("ready_")) {
                        readyNote.setText("READY 요청 거부: " + koreanIssue(detail));
                        readyNote.setTextColor(RED);
                    } else {
                        driveNote.setText("명령 거부: " + koreanIssue(detail));
                    }
                }
            }
            updateControls();
        } catch (Exception ex) {
            readyNote.setText("NUC 메시지 처리 오류 · 통신 상태 및 상세 로그를 확인하세요.");
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

    private static String koreanJob(String name) {
        switch (name) {
            case "ready_start": return "READY 시작";
            case "ready_stop": return "READY 종료";
            case "ready_status": return "READY 상태 확인";
            case "lidar_test_start": return "LiDAR 테스트 시작";
            case "lidar_test_stop": return "LiDAR 테스트 종료";
            case "drive": return "주행 시작";
            case "drive_stop": return "주행 종료";
            default: return "시스템 작업";
        }
    }

    private static int statusColor(String state) {
        if ("OK".equals(state) || "RUNNING".equals(state) || "READY".equals(state))
            return GREEN;
        if ("FAILED".equals(state) || "STALE".equals(state) || "MONITOR_STALE".equals(state))
            return RED;
        if ("WAITING".equals(state) || "STARTING".equals(state) || "STOPPING".equals(state))
            return AMBER;
        return MUTED;
    }

    private static String safetyTitle(String raw) {
        switch (raw) {
            case "CLEAR": return "Safety · 정상 주행 가능";
            case "SLOW": return "Safety · 감속 중";
            case "STOP": return "Safety · 안전 정지";
            default: return "Safety · 상태 확인 불가";
        }
    }

    private static String safetyExplanation(String raw) {
        switch (raw) {
            case "NO_RESTRICTION": return "주행 제한 없음";
            case "PATH_OBSTACLE": return "진행 경로에 장애물 감지";
            case "VERY_CLOSE_OBJECT": return "휠체어에 매우 가까운 장애물 감지";
            case "GOAL_APPROACH": return "목적지 접근으로 감속 중";
            case "CTE_LIMIT": return "경로 이탈 오차로 감속 중";
            case "CURVATURE_LIMIT": return "곡선 구간으로 감속 중";
            case "MASK_BOUNDARY": return "주행 허용 영역 경계 접근";
            case "POSE_MISSING": return "현재 위치를 확인할 수 없음";
            case "POSE_STALE": return "위치 데이터 갱신 지연";
            case "PERCEPTION_STALE": return "장애물 인식 데이터 수신 지연";
            case "PERCEPTION_NOT_OK": return "장애물 인식 결과 이상";
            case "PERCEPTION_SOURCE_STALE": return "장애물 인식 원본 데이터 지연";
            case "BAD_OBJECT": return "장애물 데이터 형식 이상";
            case "BAD_OBJECT_PROFILE": return "장애물 형상 정보 이상";
            case "STATIC_REPLAN_EARLY": return "고정 장애물 감지 · 우회 경로 판단 중";
            case "REPLAN_HOLD": return "우회 경로 처리 중 정지 유지";
            case "RELEASE_CONFIRM": return "안전 상태 복귀 확인 중";
            case "SAFETY_EXCEPTION": return "Safety 내부 오류";
            case "RECEDING_IGNORED": return "멀어지는 장애물로 판단";
            case "RECEDING_NEAR_SLOW": return "가까운 장애물로 감속 중";
            case "TEST_CLEAR_BYPASS": return "시험 모드 · 장애물 보호 우회";
            default: return raw == null || raw.isEmpty() ?
                    "상세 사유 없음" : "알 수 없는 진단 코드 (" + raw + ") · 관리자 확인 필요";
        }
    }

    private static String koreanState(String raw) {
        switch (raw) {
            case "OK": return "정상 수신";
            case "STALE": return "데이터 수신 안 됨";
            case "UNKNOWN": return "확인 불가";
            case "FAILED": return "실패";
            case "RUNNING": return "실행 중";
            case "STARTING": return "시작 중";
            case "STOPPING": return "종료 중";
            case "STOPPED": return "종료됨";
            case "READY": return "준비 완료";
            case "WAITING": return "수신 대기 중";
            case "SUCCEEDED": return "성공";
            case "IDLE": return "대기";
            case "MONITOR_STALE": return "상태 수신 중단";
            default: return raw;
        }
    }

    private static String koreanIssue(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "NUC에서 상세 오류가 전달되지 않았습니다.";
        String[][] rules = {
            {"READY startup/shutdown is in progress", "READY 시작 또는 종료 중입니다."},
            {"DRIVE session must be ended and verified", "기존 주행을 종료하고 실제 정차를 확인하세요."},
            {"READY startup is still running", "READY 시작이 완료된 뒤 다시 시도하세요."},
            {"DRIVE components remain active", "주행 프로세스가 남아 있습니다. 주행 종료를 먼저 실행하세요."},
            {"READY cleanup did not finish", "기존 READY 종료에 실패했습니다. NUC 실행 기록을 확인하세요."},
            {"Existing Livox process detected", "Livox 드라이버가 남아 있어 중복 실행을 차단했습니다."},
            {"ROS Master already exists", "기존 ROS Master가 남아 있어 테스트를 시작하지 않았습니다."},
            {"LiDAR test is already active", "LiDAR 테스트가 이미 실행 중입니다."},
            {"LiDAR test command already running", "LiDAR 테스트 명령을 처리 중입니다."},
            {"LiDAR test still starting", "LiDAR 테스트가 아직 시작 중입니다."},
            {"ROS Master did not start", "테스트용 ROS Master 실행에 실패했습니다."},
            {"Livox driver exited early", "Livox 드라이버가 시작 직후 종료됐습니다."},
            {"LiDAR monitor exited early", "LiDAR 수신 모니터가 시작 직후 종료됐습니다."},
            {"LiDAR test cleanup incomplete", "테스트 프로세스 정리가 완료되지 않았습니다. READY 시작을 중단하세요."},
            {"LiDAR test startup failed", "LiDAR 테스트 시작에 실패했습니다."},
            {"READY shutdown already requested", "READY 종료 중입니다."},
            {"READY already starting", "READY가 이미 시작 중입니다."},
            {"READY shutdown in progress", "READY 종료 작업이 진행 중입니다."},
            {"READY startup or DRIVE stop still in progress", "READY 시작 또는 주행 종료 작업이 진행 중입니다."},
            {"Previous DRIVE session must be stopped", "기존 주행 종료와 정차 확인이 먼저 필요합니다."},
            {"Cannot change route while DRIVE is active", "주행 종료 전에는 경로를 바꿀 수 없습니다."},
            {"LiDAR test active", "LiDAR 테스트를 먼저 종료하세요."},
            {"E-STOP latched", "소프트웨어 비상정지가 유지 중입니다. 별도 해제가 필요합니다."},
            {"Invalid route", "선택한 경로가 올바르지 않습니다."},
            {"POSE_NOT_ALIGNED", "현재 위치 또는 진행 방향이 경로와 맞지 않습니다."},
            {"POSE_UNAVAILABLE", "최신 지도 위치를 수신하지 못했습니다."},
            {"POSE_INVALID", "현재 위치 데이터가 올바르지 않습니다."},
            {"pose frame is not map", "위치 데이터의 좌표계가 올바르지 않습니다."},
            {"pose timestamp not fresh", "위치 데이터 갱신이 지연되고 있습니다."},
            {"NO_POSE", "위치 데이터를 수신하지 못했습니다."},
            {"NO_TRACKING", "위치추정 추적 상태가 정상적이지 않습니다."},
            {"SAFETY_POLICY_STALE", "Safety 주행 정책이 갱신되지 않았습니다."},
            {"WHEEL_STALE", "바퀴 상태 정보 수신이 지연되고 있습니다."},
            {"OUTSIDE_SAFE_ROUTE", "휠체어가 주행 허용 영역 밖에 있습니다."},
            {"NOT_AUTO_MODE", "자율주행 모드가 확인되지 않았습니다."},
            {"SCRIPT_FAILED", "주행 스크립트 실행에 실패했습니다."},
            {"competing publishers", "다른 속도 명령 발행자가 있습니다. 중복 제어를 차단했습니다."},
            {"Livox lidar silent", "LiDAR 데이터가 수신되지 않았습니다."},
            {"Livox built-in IMU silent", "LiDAR 내장 IMU 데이터가 수신되지 않았습니다."},
            {"FAST-LIO Odometry silent", "FAST-LIO 위치 데이터가 수신되지 않았습니다."},
            {"fusion object summary silent", "장애물 인식 결과가 수신되지 않았습니다."},
            {"wheel_status silent", "바퀴 상태 데이터가 수신되지 않았습니다."},
            {"ready health check failed", "READY 최종 검사에 실패했습니다."},
            {"GICP did not reach verified TRACKING", "GICP 위치추정 안정화에 실패했습니다."},
            {"ROS Master not reachable", "ROS Master와 연결할 수 없습니다."},
            {"REFUSED", "제어기가 실행 요청을 거부했습니다."}
        };
        for (String[] rule : rules) if (raw.contains(rule[0])) return rule[1];
        String converted = explainReadyIssue(raw);
        if (converted.matches(".*[가-힣].*")) return converted
                .replace("[FAIL]", "실패:").replace("[ERROR]", "오류:")
                .replace("[WARN]", "주의:");
        return "기술 오류가 발생했습니다. NUC 상세 실행 기록을 확인하세요.";
    }

    private static String readyLogLine(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        if (raw.contains("[FAIL]") || raw.contains("[ERROR]"))
            return "실패 · " + koreanIssue(raw);
        if (raw.contains("ROS Master") && raw.contains("[OK]")) return "정상 · ROS Master 실행";
        if (raw.contains("Livox") && raw.contains("[OK]")) return "정상 · LiDAR 또는 IMU 연결";
        if (raw.contains("FAST-LIO") && raw.contains("[OK]")) return "정상 · FAST-LIO 위치추정";
        if (raw.contains("GICP") && raw.contains("[OK]")) return "정상 · GICP 위치추정";
        if (raw.contains("perception") && raw.contains("[OK]")) return "정상 · 장애물 인식";
        if (raw.contains("UART") && raw.contains("[OK]")) return "정상 · UART 및 구동계";
        if (raw.contains("watchdog") && raw.contains("[OK]")) return "정상 · 정지 감시";
        if (raw.contains("READY START completed") || raw.contains("SYSTEM READY"))
            return "READY 준비 완료";
        if (raw.contains("[WAIT]")) return "위치추정 또는 센서 안정화 대기 중";
        if (raw.contains("ROS bag")) return "ROS bag 기록 관리";
        if (raw.contains("[OK]")) return "구성요소 검사 통과";
        if (raw.contains("[WARN]")) return "주의 · " + koreanIssue(raw);
        if (raw.contains("[INFO]")) return "진행 · " + koreanIssue(raw);
        return "시스템 진행 단계 · 상세 내용은 NUC 실행 기록을 확인하세요.";
    }

    private static String explainReadyIssue(String raw) {
        // Keep unknown diagnostics readable instead of hiding them.
        return raw.replace("Livox lidar silent", "LiDAR 데이터 수신 실패")
                .replace("Livox built-in IMU silent", "LiDAR 내장 IMU 데이터 수신 실패")
                .replace("ROS Master not reachable", "ROS Master 연결 실패")
                .replace("FAST-LIO Odometry silent", "FAST-LIO 위치 데이터 수신 실패")
                .replace("wheel_status silent", "모터 상태 데이터 수신 실패")
                .replace("fusion object summary silent", "장애물 인식 결과 수신 실패")
                .replace("ready health check failed", "READY 최종 검사 실패")
                .replace("Map Pose", "지도 위치추정")
                .replace("Wheel Status", "모터 상태")
                .replace("UART Diagnostics", "UART 통신 진단")
                .replace("GICP Diagnostics", "GICP 진단")
                .replace("GICP State", "GICP 위치추정 상태")
                .replace("Perception", "장애물 인식")
                .replace("GICP Exclusions", "GICP 제외 영역")
                .replace("NOT_RUNNING", "실행 안 됨")
                .replace("NOT_VERIFIED", "검증되지 않음")
                .replace("NOT_STABLE", "안정화되지 않음")
                .replace("STALE", "데이터 수신 안 됨");
    }

    private void renderLidarTest(JSONObject test) {
        if (test == null) return;
        String state = test.optString("state", "STOPPED");
        lidarTestPhase = state;
        if ("RUNNING".equals(state)) {
            lidarTestWasRunning = true;
            lidarTestLastError = null;
        }
        if ("STOPPED".equals(state)) {
            for (TextView value : lidarTestValues) {
                value.setText("미실행");
                value.setTextColor(MUTED);
            }
            if (lidarTestLastError != null) {
                lidarTestStatus.setText("LiDAR 테스트 실행 오류");
                lidarTestStatus.setTextColor(RED);
                lidarTestDetail.setText(koreanIssue(lidarTestLastError));
            } else {
                lidarTestStatus.setText("테스트 미실행");
                lidarTestStatus.setTextColor(MUTED);
                lidarTestDetail.setText(lidarTestWasRunning
                        ? "테스트 종료 후 READY를 실행하세요."
                        : "READY 전에 LiDAR 연결 상태를 점검할 수 있습니다.");
            }
            return;
        }
        boolean lidarOk = "OK".equals(test.optString("lidar"));
        boolean imuOk = "OK".equals(test.optString("imu"));
        boolean live = "RUNNING".equals(state);
        boolean monitorStale = "MONITOR_STALE".equals(state);
        lidarTestStatus.setText(monitorStale ? "테스트 상태 수신 중단" :
                !live ? "테스트 상태 확인 중" :
                lidarOk && imuOk ? "LiDAR · IMU 정상 수신 중" : "센서 데이터 수신 확인 중");
        lidarTestStatus.setTextColor(monitorStale ? RED :
                live && lidarOk && imuOk ? GREEN : AMBER);
        String[] codes = {
                test.optString("master", "UNKNOWN"), test.optString("driver", "UNKNOWN"),
                test.optString("lidar", "WAITING"), test.optString("imu", "WAITING")
        };
        for (int i = 0; i < codes.length; i++) {
            lidarTestValues[i].setText(koreanState(codes[i]));
            lidarTestValues[i].setTextColor(statusColor(codes[i]));
        }
        lidarTestDetail.setText(monitorStale
                ? "상태 모니터 갱신이 끊겼습니다. 연결과 NUC 로그를 확인하세요."
                : "케이블을 움직이며 네 항목의 변화를 확인하세요. 종료는 [테스트 종료]를 눌러야 합니다. READY는 자동 재시작되지 않습니다.");
    }

    private void renderTelemetry(JSONObject f) {
        renderLidarTest(f.optJSONObject("lidar_test"));
        JSONObject ready = f.optJSONObject("ready");
        if (ready != null && "STARTING".equals(lidarTestPhase)) {
            JSONObject jobsForTest = ready.optJSONObject("jobs");
            JSONObject testJob = jobsForTest == null ? null : jobsForTest.optJSONObject("lidar_test_start");
            if (testJob != null) {
                String step = testJob.optString("detail", "");
                if (step.contains("[STEP]")) {
                    lidarTestDetail.setText(step.replace("[STEP]", "진행:"));
                } else {
                    lidarTestDetail.setText("기존 READY 정리 또는 테스트 환경을 준비하고 있습니다.");
                }
            }
        }
        if (ready != null) {
            String state = ready.optString("state", "UNKNOWN");
            readyPhase = state;
            readyState.setText("READY: " + koreanState(state));
            readyState.setTextColor(statusColor(state));
            JSONObject components = ready.optJSONObject("components");
            if (components != null) {
                for (int i = 0; i < COMPONENT_KEYS.length; i++) {
                    String value = components.optString(COMPONENT_KEYS[i], "UNKNOWN");
                    boolean active = "READY".equals(state) || "STARTING".equals(state) ||
                            "FAILED".equals(state);
                    readyComponentValues[i].setText(active ? koreanState(value) : "시스템 미실행");
                    readyComponentValues[i].setTextColor(active ? statusColor(value) : MUTED);
                }
            }
            JSONObject jobs = ready.optJSONObject("jobs");
            if (jobs != null) {
                StringBuilder log = new StringBuilder();
                for (String job : new String[]{"ready_start", "ready_status", "ready_stop"}) {
                    JSONObject info = jobs.optJSONObject(job);
                    if (info != null) {
                        log.append(koreanJob(job)).append(" · ")
                                .append(koreanState(info.optString("state", "UNKNOWN")))
                                .append("\n");
                        JSONArray tail = info.optJSONArray("tail");
                        if (tail != null && tail.length() > 0) {
                            log.append(readyLogLine(tail.optString(tail.length() - 1))).append("\n");
                        }
                    }
                }
                // The latest tail alone hides the first failure and leaves stale
                // cleanup messages after a successful run. Keep full run history.
                JSONArray history = ready.optJSONArray("history");
                if (history != null) {
                    log.setLength(0);
                    String lastLine = "";
                    for (int i = 0; i < history.length(); i++) {
                        String nextLine = readyLogLine(history.optString(i));
                        if (!nextLine.isEmpty() && !nextLine.equals(lastLine)) {
                            log.append(nextLine).append("\n");
                            lastLine = nextLine;
                        }
                    }
                }
                readyLogs.setText(log.toString().trim());
            }
            JSONArray failures = ready.optJSONArray("failures");
            if ("FAILED".equals(state)) {
                StringBuilder problems = new StringBuilder("READY 실행 실패");
                if (failures != null && failures.length() > 0) {
                    problems.append("\n실패 원인:");
                    for (int i = 0; i < failures.length(); i++) {
                        problems.append("\n• ").append(koreanIssue(failures.optString(i)));
                    }
                } else {
                    problems.append("\n실패한 단계를 실행 기록에서 확인하세요.");
                }
                readyNote.setText(problems.toString());
                readyNote.setTextColor(RED);
            } else if ("READY".equals(state)) {
                readyNote.setText("READY 완료 · 필수 검사 통과");
                readyNote.setTextColor(GREEN);
            } else if ("STARTING".equals(state)) {
                readyNote.setText("READY 실행 중 · 순서대로 검사하고 있습니다.");
                readyNote.setTextColor(MUTED);
            }
        }
        String route = f.optString("selected_route", "");
        if (!route.isEmpty() && Arrays.asList(ROUTES).contains(route) &&
                !route.equals(selectedRoute)) {
            selectedRoute = route;
            routeSpinnerUpdating = true;
            routeSpinner.setSelection(Arrays.asList(ROUTES).indexOf(route));
            routeSpinnerUpdating = false;
            currentRoute.setText("선택 경로: " + ROUTE_LABELS[Arrays.asList(ROUTES).indexOf(route)]);
            send("route", null);
        }
        JSONObject wheel = f.optJSONObject("wheel");
        String mode = wheel == null ? "UNKNOWN" : wheel.optString("mode_name", "UNKNOWN");
        wheelMode = mode;
        if ("MANUAL".equals(mode)) {
            wheelModeText.setText("현재 모드: 수동 조작 모드 (조종간)");
            wheelModeText.setTextColor(AMBER);
        } else if ("AUTO".equals(mode)) {
            wheelModeText.setText("현재 모드: 자율주행 모드");
            wheelModeText.setTextColor(GREEN);
        } else {
            wheelModeText.setText("현재 모드: 확인 불가");
            wheelModeText.setTextColor(MUTED);
        }
        JSONObject pose = f.optJSONObject("pose");
        mapView.setPose(pose);
        String phase = f.optString("drive_phase", "UNKNOWN");
        currentDrivePhase = phase;
        driveConfirmed = f.optBoolean("drive_confirmed", false);
        if ("GOAL_ARRIVED".equals(phase)) {
            driveNote.setText("목적지에 도착했습니다. 정차 후 [주행 종료]를 눌러 세션을 마무리하세요.");
        } else if ("NEEDS_STOP".equals(phase)) {
            driveNote.setText("수동 개입 또는 주행 프로세스 종료 · [주행 종료]로 정차 상태를 확인하세요.");
        } else if ("STOP_UNVERIFIED".equals(phase)) {
            driveNote.setText("정지 확인 실패 · 실제 바퀴 정지와 수동 모드를 확인한 뒤 [주행 종료]를 다시 누르세요.");
        }
        JSONObject safety = f.optJSONObject("safety");
        if (driveConfirmed && ("RUNNING".equals(phase) || "STATUS_STALE".equals(phase))) {
            if (safety == null || "STATUS_STALE".equals(phase)) {
                showUnknownSafety("최신 Safety 데이터를 확인할 수 없습니다.");
            } else {
                String state = safety.optString("state", "UNKNOWN");
                safetyState.setText(safetyTitle(state));
                safetyState.setTextColor("CLEAR".equals(state) ? GREEN :
                        "SLOW".equals(state) ? AMBER : "STOP".equals(state) ? RED : MUTED);
                String reason = safety.optString("reason", "이유 정보 없음");
                String replanState = safety.optString("replan_state", "IDLE");
                if ("STOP".equals(state) && "FAILED".equals(replanState)) {
                    safetyReason.setText("우회 경로 생성 실패 · 정지 중");
                } else if ("STOP".equals(state) &&
                        ("PLANNING".equals(replanState) ||
                         "STAGING".equals(replanState) ||
                         "COMMITTING".equals(replanState))) {
                    safetyReason.setText("우회 경로 생성 중...");
                } else {
                    safetyReason.setText(safetyExplanation(reason));
                }
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
