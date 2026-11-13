package com.example.jetsonbtmonitor;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline UI-only test source. Deliberately has no Android Bluetooth, ROS,
 * network, filesystem, shell, or motor interfaces.
 *
 * Frames use the same JSON protocol as the NUC bridge, but are marked SIMULATED.
 * This is NOT verification of real physical stopping or transport reliability.
 */
public final class IndoorDriveSimulator {
    private String ready = "READY";
    private String phase = "IDLE";
    private String route = "A_to_D5";
    private String wheelMode = "MANUAL";
    private boolean confirmed = false;
    private boolean wheelsStopped = true;
    private boolean nextStopNoResult = false;
    private boolean nextStopFails = false;

    private JSONObject frame(String type) throws JSONException {
        return new JSONObject().put("type", type).put("simulated", true);
    }

    private JSONObject ack(String command, boolean ok, String detail) throws JSONException {
        return frame("ack").put("command", command).put("ok", ok).put("detail", detail);
    }

    private JSONObject result(String name, boolean ok, String detail) throws JSONException {
        return frame("job_result").put("job", name).put("ok", ok).put("detail", detail);
    }

    private JSONObject stopResult(String status, String reason) throws JSONException {
        return frame("drive_result").put("status", status).put("reason", reason)
                .put("message", "실내 모의 이벤트: " + reason);
    }

    public String phase() { return phase; }

    /** Scenario buttons prepare a failure without touching any hardware. */
    public List<JSONObject> scenario(String selected) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        ready = "READY";
        wheelMode = "MANUAL";
        confirmed = false;
        wheelsStopped = true;
        nextStopNoResult = false;
        nextStopFails = false;
        if ("stale".equals(selected)) {
            phase = "STOP_UNVERIFIED";
            out.add(ack("drive_start", false,
                    "Previous DRIVE session must be stopped and verified before restarting"));
        } else if ("no_result".equals(selected)) {
            phase = "STOP_UNVERIFIED";
            nextStopNoResult = true;
        } else if ("stop_failed".equals(selected)) {
            phase = "STOP_UNVERIFIED";
            nextStopFails = true;
        } else if ("wheel_unknown".equals(selected)) {
            phase = "STOP_UNVERIFIED";
            wheelsStopped = false;
            wheelMode = "UNKNOWN";
        } else {
            phase = "IDLE";
        }
        out.add(telemetry());
        return out;
    }

    /** Process commands exactly like a JSON-line bridge, but strictly locally. */
    public List<JSONObject> handle(String command, String routeArg) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        switch (command) {
            case "status":
                out.add(ack(command, true, "SIMULATED status"));
                break;
            case "route":
                out.add(ack(command, true, "SIMULATED route"));
                out.add(frame("route").put("route", route)
                        .put("points", new org.json.JSONArray()));
                break;
            case "route_select":
                if (routeArg != null && !routeArg.isEmpty()) {
                    route = routeArg;
                    out.add(ack(command, true, "SIMULATED route selected"));
                } else {
                    out.add(ack(command, false, "No route selected"));
                }
                break;
            case "drive_start":
                if (!"READY".equals(ready) || !"MANUAL".equals(wheelMode)) {
                    out.add(ack(command, false, "READY/MANUAL required"));
                } else if (!("IDLE".equals(phase) || "STOPPED".equals(phase)
                        || "REJECTED".equals(phase) || "FAILED".equals(phase))) {
                    out.add(ack(command, false,
                            "Previous DRIVE session must be stopped and verified before restarting"));
                } else {
                    phase = "RUNNING";
                    confirmed = true;
                    wheelMode = "AUTO";
                    wheelsStopped = false;
                    out.add(ack(command, true, "SIMULATED drive accepted"));
                    out.add(stopResult("STARTED", "ROS_RUNNING"));
                }
                break;
            case "drive_stop":
                out.add(ack(command, true, "SIMULATED stop accepted"));
                if (nextStopNoResult) {
                    // Stuck STOPPING: no job_result, no STOPPED event.
                    nextStopNoResult = false;
                    phase = "STOPPING";
                } else if (nextStopFails) {
                    nextStopFails = false;
                    phase = "STOP_UNVERIFIED";
                    wheelMode = "MANUAL";
                    wheelsStopped = true;
                    confirmed = false;
                    out.add(stopResult("STOP_UNVERIFIED", "STOP_SCRIPT_FAILED"));
                    out.add(result("drive_stop", false, "SIMULATED STOP check failed"));
                } else {
                    phase = "STOPPED";
                    confirmed = false;
                    wheelMode = "MANUAL";
                    wheelsStopped = true;
                    out.add(stopResult("STOPPED", "STOP_VERIFIED"));
                    out.add(result("drive_stop", true, "SIMULATED stop verified"));
                }
                break;
            case "drive_reconcile":
                if (confirmed || "RUNNING".equals(phase) || "AUTO".equals(wheelMode)
                        || !wheelsStopped || !"MANUAL".equals(wheelMode)) {
                    out.add(ack(command, true, "SIMULATED verification started"));
                    out.add(result("drive_reconcile", false,
                            "SIMULATED: wheel stop/MANUAL not verified"));
                } else {
                    phase = "STOPPED";
                    out.add(ack(command, true, "SIMULATED verification started"));
                    out.add(stopResult("STOPPED", "RECONCILE_VERIFIED"));
                    out.add(result("drive_reconcile", true,
                            "SIMULATED: MANUAL and both stopped"));
                }
                break;
            case "ready_start":
                ready = "READY";
                out.add(ack(command, true, "SIMULATED READY"));
                out.add(result("ready_start", true, "SIMULATED"));
                break;
            case "ready_stop":
                if (confirmed || "RUNNING".equals(phase)) {
                    out.add(ack(command, false, "DRIVE must stop before READY shutdown"));
                } else {
                    ready = "STOPPED";
                    out.add(ack(command, true, "SIMULATED READY stop"));
                    out.add(result("ready_stop", true, "SIMULATED"));
                }
                break;
            case "lidar_test_start":
            case "lidar_test_stop":
                out.add(ack(command, false, "Indoor mock does not run LiDAR hardware"));
                break;
            default:
                out.add(ack(command, false, "Unsupported simulated command: " + command));
        }
        out.add(telemetry());
        return out;
    }

    public JSONObject telemetry() throws JSONException {
        JSONObject readyData = new JSONObject().put("state", ready)
                .put("components", new JSONObject())
                .put("jobs", new JSONObject());
        return frame("telemetry")
                .put("protocol_version", 4)
                .put("ready", readyData)
                .put("lidar_test", new JSONObject().put("state", "STOPPED"))
                .put("selected_route", route)
                .put("active_route", confirmed ? route : JSONObject.NULL)
                .put("drive_phase", phase)
                .put("drive_confirmed", confirmed)
                .put("drive_reconcile_running", false)
                .put("wheel", new JSONObject().put("mode_name", wheelMode))
                .put("safety", confirmed
                        ? new JSONObject().put("state", "CLEAR").put("reason", "SIMULATED")
                        : JSONObject.NULL)
                .put("drive", confirmed
                        ? new JSONObject().put("state", "RUNNING") : JSONObject.NULL);
    }
}
