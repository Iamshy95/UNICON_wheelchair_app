# AGENTS.md — UNICON FF–PID Android + NUC Bluetooth

## Scope (current project)

This repository is the **ROS1 Noetic FF–PID/Safety wheelchair operator app**, not the old Jetson/ROS2/Wi-Fi monitoring project. Its parts are:

- Android Java: `PidOperatorActivity.java`, `PidRouteMapView.java`, `BluetoothWheelchairClient.java`.
- NUC ROS1/BlueZ bridge: `nuc/pid_bt_bridge.py`.
- Detailed bridge contract and instructions: `nuc/README.md`.
- Source attribution/licensing: `LICENSE`, `NOTICE.md`, root `README.md`.

The original team's app and independent NUC `ros1_bluetooth_bridge.py` must **not** be modified or interrupted. The dedicated app/bridge UUID is `9a1f17cc-18d2-45f0-b6e7-1b0e257a902e` (default RFCOMM channel 7; check the actual NUC before deployment).

## User-approved product decisions

- Two screens (tabs): **관리자** (one NUC Bluetooth connect button; READY start/stop, health, errors) and **탑승자** (six A/D4/D5 routes; drive start/end; preflight guidance; live Safety status).
- No login, in-app device picker, disconnect button, camera, Wi-Fi controls, or app emergency-stop button.
- Hardware/physical joystick stop remains the emergency response. `pid_stop.sh` is an ordinary verified drive-end request, **not** a certified emergency-stop device.
- **Map implementation is staged**: current interim view shows the selected path, start/goal and current heading/location without a background image; a properly aligned **PCD-derived 2D map** is a **required future final milestone**, not permanently cancelled. Do not use `common_drivable_mask.pgm` as a passenger map illustration.
- Routes are fetched from the NUC during Bluetooth operation rather than bundled into the APK. Real-time pose/Safety are also received from the NUC.

## Ground truth & paths

- Separate PID project: https://github.com/Iamshy95/UNICON_wheelchair_PID
- Actual NUC PID scripts are deployed within `<REPO_ROOT>/src/pid/scripts/`.
- Actual FF–PID route assets are resolved from `<REPO_ROOT>/routes/route2_manual_20261031/`. The separate PID GitHub project's `waypoints/` is a source/archive location; it is **not automatically** the NUC route directory.
- Bridge accepts `--pid-scripts` and `--routes-dir`; when `--routes-dir` is omitted it resolves from the PID scripts path. Ensure the UI and FF–PID read the same real route JSON.
- Forward exactly the six allowed route IDs; no arbitrary shell commands.
- FF–PID and the preflight script transform the raw `/fast_lio_icp/pose` into a **chair-centre** reference using `body_frame.py` and route-specific metadata. The bridge's raw GICP pose before drive confirmation is not necessarily chair-centred. Do not assert that all pose sources are equivalent.
- The bridge must never directly publish `/cmd_vel`, alter FF–PID or Safety policy, expose occupied-driving `--test-clear`, or change existing ROS bridge services.

## Safety & protocol

- One newline-delimited JSON object per Bluetooth message. Commands include `status`, `route`, `route_select`, `ready_start`, `ready_stop`, `drive_start`, `drive_stop`.
- Treat `ack.ok=true` as command acceptance, **not** confirmed READY, movement, or physical stop.
- Report drive success only after the same route's actual fresh `/ff_pid/status: RUNNING` is confirmed, not just when START service accepts it.
- Route/pose data must use compatible ROS `map` frames and matching chair-centre references. Do not fabricate localization, route geometry, Safety data or readiness when offline.
- Safety `STOP` is a temporary controller hold and differs from ending a drive. Missing, stale, or disconnected telemetry must be reported as unknown, not healthy. Never present a stale last-known pose as live.
- Preserve operator-initiated drive STOP semantics even when telemetry becomes stale. Any changes to disconnection or retry behavior require reviewing socket/thread ownership and whether an old reader can close a newer connection.
- If a requested action is unsafe or cannot be verified on an actual device, mark the unverified state and make no safety claims.

## Development practice

- Keep Android code Java/AndroidX and the present programmatic UI unless the user explicitly requests a redesign.
- Keep the NUC bridge Python 3/ROS1/BlueZ; preserve message schema in both sender and receiver.
- For source edits, review the corresponding scripts and JSON payloads across Android, NUC, and PID; add tests where practical.
- Distinguish **code committed**, **Android Studio build**, **emulator run**, **real Android ↔ NUC Bluetooth test**, and **occupied-driving safety tests**.
- Suggested checks (run only in an available environment): `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`, `python3 -m py_compile nuc/pid_bt_bridge.py`. Never claim that a command ran unless it actually did.
- Do not overwrite the original team's bridge or deploy code to an actual NUC without verifying existing processes, real paths and hardware conditions.
- Document actual changes in `README.md` and `nuc/README.md`. Do not carry forward obsolete Jetson/ROS2/Wi-Fi/camera implementation descriptions.
