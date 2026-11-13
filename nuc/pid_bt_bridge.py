#!/usr/bin/env python3
"""Independent Bluetooth SPP <-> ROS1 FF-PD operator bridge.

This bridge does not publish /cmd_vel or implement safety logic.
Run alongside the team's *different* SPP profile (different UUID and channel).
Requires python3-dbus, python3-gi, BlueZ and ROS Noetic on the NUC.
"""
import argparse
import json
import logging
import math
import os
import signal
import sys
from pathlib import Path
from collections import deque
import socket
import subprocess
import threading
import time

ROUTES = ("A_to_D4", "A_to_D5", "D4_to_A", "D4_to_D5", "D5_to_A", "D5_to_D4")
PID_SPP_UUID = "9a1f17cc-18d2-45f0-b6e7-1b0e257a902e"
PID_SPP_CHANNEL = 7
LOG = logging.getLogger("pid_bt_bridge")


class Bridge:
    def __init__(self, scripts, routes_dir):
        self.scripts = Path(scripts).expanduser().resolve()
        self.routes_dir = Path(routes_dir).expanduser().resolve()
        self.lock = threading.RLock()
        self.selected_route = "A_to_D5"
        self.active_route = None
        self.estop_latched = False
        self.jobs = {}
        self.job_status = {"name": None, "state": "IDLE", "detail": "", "tail": []}
        self.ready_state = "UNKNOWN"
        self.latest = {}
        self.ros_connected = False
        self.rospy = None
        self._seq = 0
        self._events = deque(maxlen=64)
        self.job_states = {}
        self.last_preflight = None
        self.last_drive_event = None
        self.drive_phase = "IDLE"
        self.drive_requested_at = None
        self.drive_accepted = False
        self.drive_confirmed = False
        self.drive_rejection_sent = False
        self.drive_terminal_reason = None  # GOAL_REACHED / MANUAL_INTERVENTION / unexpected exit
        self.ready_stopping = False
        self.stop_alarm = None
        self._pose_transform_cache = {}
        self._pose_transform_errors = set()
        self.ready_history = deque(maxlen=100)
        self.ready_failures = []
        self.ready_run_id = 0
        self.lidar_test_state = Path(os.environ.get(
            "PID_LIDAR_TEST_STATE_DIR",
            str(Path.home() / ".local/state/pid_lidar_test")))

    def _lidar_test(self):
        """Read test-owned snapshot without joining its temporary ROS master."""
        if not (self.lidar_test_state / "active").exists():
            if self._running("lidar_test_start"):
                return {"state": "STARTING", "lidar": "WAITING", "imu": "WAITING"}
            if self._running("lidar_test_stop"):
                return {"state": "STOPPING", "lidar": "UNKNOWN", "imu": "UNKNOWN"}
            return {"state": "STOPPED", "lidar": "UNKNOWN", "imu": "UNKNOWN"}
        try:
            result = json.loads((self.lidar_test_state / "status.json").read_text(encoding="utf-8"))
            if not isinstance(result, dict):
                raise ValueError("Invalid LiDAR test status")
            age = time.time() - float(result.get("timestamp", 0))
            if result.get("state") == "RUNNING" and (age < -3 or age > 4):
                result = dict(result)
                result.update(state="MONITOR_STALE", lidar="UNKNOWN", imu="UNKNOWN")
            return result
        except (OSError, ValueError, TypeError):
            return {"state": "STARTING", "lidar": "WAITING", "imu": "WAITING"}

    def _emit(self, event):
        """Remember structured events for every active Bluetooth session."""
        with self.lock:
            self._seq += 1
            entry = dict(event)
            entry.setdefault("stamp", time.time())
            entry["seq"] = self._seq
            self._events.append(entry)

    def event_seq(self):
        with self.lock:
            return self._seq

    def events_after(self, seq):
        with self.lock:
            return [dict(event) for event in self._events if event["seq"] > seq]

    def _reject_drive(self, reason, message):
        """Rejection is not the same thing as an accepted shell launch."""
        with self.lock:
            if self.drive_rejection_sent or self.drive_confirmed:
                return
            self.drive_rejection_sent = True
            self.drive_phase = "REJECTED"
            self._emit({"type": "drive_result", "status": "REJECTED",
                        "reason": reason, "route": self.active_route,
                        "message": message, "preflight": self.last_preflight})

    def _consume_script_event(self, name, line):
        if name != "drive" or not line.startswith("{"):
            return
        try:
            obj = json.loads(line)
        except ValueError:
            return
        if not isinstance(obj, dict) or obj.get("type") not in ("drive_preflight", "drive_event"):
            return
        with self.lock:
            if obj.get("route") != self.active_route:
                LOG.warning("Ignoring mismatched drive event route: %r", obj.get("route"))
                return
            if obj["type"] == "drive_preflight":
                self.last_preflight = obj
                self._emit(obj)
                if not obj.get("ok", False):
                    self._reject_drive(obj.get("reason", "POSE_NOT_ALIGNED"),
                                       obj.get("message", "출발 위치 점검 실패"))
            else:
                self.last_drive_event = obj
                self._emit(obj)
                if obj.get("status") == "STARTED":
                    if self.drive_phase != "STOPPING":
                        self.drive_accepted = True
                        self.drive_phase = "START_REQUEST_ACCEPTED"
                elif obj.get("status") == "REJECTED":
                    self._reject_drive(obj.get("reason", "SCRIPT_CHECK_FAILED"),
                                       obj.get("message", "주행 시작이 거부되었습니다."))
                elif obj.get("status") in ("GOAL_REACHED", "MANUAL_INTERVENTION"):
                    self._mark_drive_interrupted(obj["status"])
                elif obj.get("status") == "STOP_VERIFIED":
                    # A terminal Ctrl+C or external pid_stop.sh completed
                    # physical STOP; no additional app acknowledgement needed.
                    # A goal arrival alone remains pending operator completion.
                    if self.drive_terminal_reason != "GOAL_REACHED" and self.drive_phase != "STOPPING":
                        self.drive_phase = "STOPPED"
                        self.active_route = None
                        self.drive_confirmed = False
                        self.drive_terminal_reason = None
                        self._emit({"type": "drive_result", "status": "STOPPED",
                                    "reason": "TERMINAL_STOP_VERIFIED",
                                    "message": "터미널 주행 종료 및 MANUAL 정차 확인 완료"})
                elif obj.get("status") == "STOP_UNVERIFIED":
                    if self.drive_phase != "STOPPING":
                        self.drive_phase = "STOP_UNVERIFIED"
                        self._emit({"type": "drive_result", "status": "STOP_UNVERIFIED",
                                    "reason": "TERMINAL_STOP_FAILED",
                                    "message": "터미널 주행 종료 확인 실패"})

    def _mark_drive_interrupted(self, reason):
        """Latch a completed goal or manual intervention until explicit DRIVE STOP.

        Controller ROS messages may vanish when the node exits. A latched
        phase prevents the UI from treating an exited DRIVE process as IDLE.
        """
        with self.lock:
            if self.drive_phase in ("STOPPING", "STOPPED", "STOP_UNVERIFIED"):
                return
            if not self.drive_confirmed:
                return
            if reason == "GOAL_REACHED":
                self.drive_terminal_reason = reason
                self.drive_phase = "GOAL_ARRIVED"
            elif reason == "MANUAL_INTERVENTION" and self.drive_terminal_reason != "GOAL_REACHED":
                self.drive_terminal_reason = reason
                self.drive_phase = "NEEDS_STOP"

    def _confirm_drive(self, drive, received_at):
        """Only a fresh ROS RUNNING status may confirm actual start."""
        with self.lock:
            if (not self.drive_accepted or self.drive_confirmed or self.drive_rejection_sent
                    or self.drive_phase == "STOPPING" or self.active_route is None
                    or received_at < (self.drive_requested_at or float("inf"))):
                return
            if drive.get("state") != "RUNNING":
                return
            # A stale status from a previous controller must not confirm START.
            # FF-PD node uses the ROS private parameter /ff_pid/route.
            if not self.rospy:
                return
            try:
                actual_route = self.rospy.get_param("/ff_pid/route", None)
            except Exception:
                return
            if actual_route != self.active_route:
                return
            self.drive_confirmed = True
            self.drive_phase = "RUNNING"
            self._emit({"type": "drive_result", "status": "STARTED",
                        "reason": "ROS_RUNNING", "route": self.active_route,
                        "message": "FF-PD RUNNING 상태 확인"})


    def _script(self, filename):
        # The optional read-only verifier is shipped beside this bridge, not
        # in the team PID control scripts. Do not touch deployed FF-PD code.
        path = (Path(__file__).resolve().with_name(filename)
                if filename == "pid_drive_reconcile.sh" else self.scripts / filename)
        if not path.is_file():
            raise RuntimeError("Missing NUC PID script: " + str(path))
        return str(path)

    def _record_line(self, name, line):
        line = line.rstrip()[:4096]
        with self.lock:
            job = self.job_states.get(name)
            if job is not None:
                tail = job["tail"]
                tail.append(line[:240])
                del tail[:-12]
                job["detail"] = line[:240]
                if "[FAIL]" in line or "[ERROR]" in line:
                    # Retain the FIRST concrete failure; cleanup logs must
                    # not replace the actionable reason shown to the operator.
                    job.setdefault("failure_detail", line[:300])
                if self.job_status["name"] == name:
                    self.job_status = dict(job)
            self._consume_script_event(name, line)
            if name == "lidar_test_start" and "[STEP] READY 종료 확인 완료" in line:
                # The test script has positively completed READY teardown.
                # Reconcile stale bridge state even if later test startup fails.
                self.ready_state = "STOPPED"
                self.latest.clear()
            if name == "ready_start" and line:
                self.ready_history.append(line[:300])
                # pid_ready_monitor.py emits one [ERROR] for EACH failed
                # subsystem; preserve all, not only the final shell [FAIL].
                for marker in ("[FAIL]", "[ERROR]"):
                    if marker in line:
                        failure = line.split(marker, 1)[1].strip()
                        if failure and failure not in self.ready_failures:
                            self.ready_failures.append(failure[:240])
        if line:
            LOG.info("%s: %s", name, line)

    def _run_job(self, name, args, long_lived=False, end_callback=None):
        with self.lock:
            if self._running(name):
                raise RuntimeError(name + " is already running")
            cmd = ["/bin/bash", self._script(args[0])] + args[1:]
            # A separate process session survives Bluetooth disconnects.
            proc = subprocess.Popen(
                cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
                start_new_session=True)
            self.jobs[name] = proc
            state = {"name": name, "state": "RUNNING",
                     "detail": " ".join(args), "tail": []}
            self.job_states[name] = state
            self.job_status = dict(state)

        def follow():
            try:
                for line in proc.stdout:
                    self._record_line(name, line)
                code = proc.wait()
            except Exception as exc:
                code = -1
                LOG.exception("job stream error: %s", exc)
            finally:
                if proc.stdout:
                    proc.stdout.close()
            with self.lock:
                if self.jobs.get(name) is proc:
                    self.jobs.pop(name, None)
                job = self.job_states.get(name)
                if name == "drive_reconcile" and code == 0:
                    # Read-only verification must not overwrite concurrent STOP/DRIVE.
                    if (self._running("drive") or self._running("drive_stop")
                            or self.ready_stopping
                            or self.drive_phase in ("CHECKING", "START_REQUEST_ACCEPTED",
                                                    "RUNNING", "STOPPING")):
                        code = 5
                        if job is not None:
                            job["failure_detail"] = "DRIVE/STOP state changed during verification"
                    else:
                        self.drive_phase = "STOPPED"
                        self.active_route = None
                        self.drive_confirmed = False
                        self.drive_accepted = False
                        self.drive_rejection_sent = False
                        self.drive_terminal_reason = None
                        self.last_preflight = None
                        LOG.info("drive_reconcile: verified stopped MANUAL; stale session cleared")
                        self._emit({"type": "drive_result", "status": "STOPPED",
                                    "reason": "RECONCILE_VERIFIED",
                                    "message": "정지·MANUAL·주행 프로세스 종료 확인 완료"})
                if job is not None:
                    job["state"] = "SUCCEEDED" if code == 0 else "FAILED"
                    job["exit_code"] = code
                    if self.job_status["name"] == name:
                        self.job_status = dict(job)
                if name == "lidar_test_start" and code == 0:
                    # The isolated test has shut down any registered READY stack.
                    self.ready_state = "STOPPED"
                if name == "ready_start":
                    self.ready_state = "READY" if code == 0 else "FAILED"
                    if code == 0:
                        self.ready_failures.clear()
                elif name == "ready_stop":
                    self.ready_stopping = False
                    if code == 0:
                        self.ready_state = "STOPPED"
                elif name == "drive":
                    if self.drive_phase not in ("STOPPING", "STOPPED", "STOP_UNVERIFIED"):
                        if not self.drive_confirmed:
                            self._reject_drive("SCRIPT_FAILED",
                                               (job or {}).get("detail", "DRIVE script exited"))
                        elif self.drive_terminal_reason == "GOAL_REACHED":
                            self.drive_phase = "GOAL_ARRIVED"
                        else:
                            # Manual intervention or unexpected controller exit:
                            # an explicit STOP verification is still required.
                            self.drive_phase = "NEEDS_STOP"
                            self.drive_terminal_reason = (
                                self.drive_terminal_reason or "DRIVE_EXITED")
                    # Do not clear active_route on exit: the operator must
                    # acknowledge and verify the end of this driving session.
                elif name == "drive_stop":
                    if code == 0:
                        self.drive_phase = "STOPPED"
                        self.active_route = None
                        self.drive_terminal_reason = None
                        self.drive_confirmed = False
                        self._emit({"type": "drive_result", "status": "STOPPED",
                                    "reason": "STOP_VERIFIED",
                                    "message": "MANUAL 및 정차 확인 완료"})
                    else:
                        self.drive_phase = "STOP_UNVERIFIED"
                        self._emit({"type": "drive_result", "status": "STOP_UNVERIFIED",
                                    "reason": "STOP_SCRIPT_FAILED",
                                    "message": "실제 정지를 확인하지 못했습니다.",
                                    "exit_code": code})
                self._emit({"type": "job_result", "job": name,
                            "ok": code == 0, "exit_code": code,
                            "detail": ((job or {}).get("failure_detail") if code != 0 else None)
                                      or (job or {}).get("detail", "")})
            if end_callback:
                end_callback(code)
            LOG.info("%s exited %s", name, code)

        threading.Thread(target=follow, daemon=True, name="job_" + name).start()
        return {"ok": True, "detail": name + " requested (not yet confirmed)"}

    def _running(self, name):
        p = self.jobs.get(name)
        return p is not None and p.poll() is None

    def _cancel_drive_launch(self):
        """Cancel a still-starting DRIVE shell before asking pid_stop.sh to stop."""
        with self.lock:
            proc = self.jobs.get("drive")
            if proc is None or proc.poll() is not None:
                return
            if self.drive_confirmed:
                return  # Established DRIVE is stopped via pid_stop.sh.
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            except OSError:
                LOG.exception("Unable to interrupt DRIVE preflight process")

    def _shutdown_ready(self):
        """Verify stopped MANUAL wheels before READY stack teardown."""
        try:
            with self.lock:
                active = (self._running("drive") or self._running("drive_stop")
                          or self.drive_phase in ("RUNNING", "START_REQUEST_ACCEPTED", "STOPPING", "GOAL_ARRIVED", "NEEDS_STOP", "STOP_UNVERIFIED"))
            if active:
                with self.lock:
                    self.drive_phase = "STOPPING"
                self._cancel_drive_launch()
                command = ["/bin/bash", self._script("pid_stop.sh")]
                stopped = subprocess.run(command, stdin=subprocess.DEVNULL,
                                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         text=True, timeout=35)
                self._emit({"type": "job_result", "job": "ready_pre_stop",
                            "ok": stopped.returncode == 0,
                            "exit_code": stopped.returncode,
                            "detail": stopped.stdout[-1200:]})
                if stopped.returncode != 0:
                    with self.lock:
                        self.drive_phase = "STOP_UNVERIFIED"
                    raise RuntimeError("주행 정지가 확인되지 않아 READY 종료를 취소했습니다.")
                with self.lock:
                    self.drive_phase = "STOPPED"
                    self.drive_terminal_reason = None
                    self.drive_confirmed = False
                    self.active_route = None
            self._run_job("ready_stop", ["pid_ready.sh", "stop"])
        except Exception as exc:
            LOG.warning("READY stop refused: %s", exc)
            with self.lock:
                self.ready_state = "FAILED"
            self._emit({"type": "job_result", "job": "ready_stop",
                        "ok": False, "detail": str(exc)})
        finally:
            with self.lock:
                self.ready_stopping = False


    def _chair_centre_transform(self, route_name):
        """Use the same route-specific chair-centre correction as PID preflight.

        GICP publishes a map-frame pose of the FAST-LIO body origin, not the
        chair centre. This transform is evaluated in the *body* frame, so the
        mounting offset rotates with yaw. Never fall back to raw GICP as if
        it were chair-centred when route metadata/imports are missing.
        """
        with self.lock:
            if route_name in self._pose_transform_cache:
                return self._pose_transform_cache[route_name]
        path = self.routes_dir / (route_name + "_waypoints.json")
        with path.open(encoding="utf-8") as stream:
            metadata = json.load(stream)
        if (metadata.get("frame") != "map"
                or metadata.get("route_key") != route_name
                or metadata.get("reference_point") != "chair_centre"):
            raise ValueError("Invalid chair-centred map route: " + str(path))
        profile = metadata["body_frame_profile"]
        localization_scripts = self.scripts.parents[1] / "static_livox_localization" / "scripts"
        if not (localization_scripts / "body_frame.py").is_file():
            raise FileNotFoundError("Missing body_frame.py: " + str(localization_scripts))
        scripts_path = str(localization_scripts)
        if scripts_path not in sys.path:
            sys.path.insert(0, scripts_path)
        from body_frame import pose_correction, reference_correction, route_chair_centre
        # Both pid_drive.sh and pid_drive_check.py use the builtin IMU profile.
        correction = pose_correction("builtin", str(profile)) @ reference_correction(
            str(metadata["reference_point"]), route_chair_centre(metadata))
        with self.lock:
            self._pose_transform_cache[route_name] = correction
        return correction

    def _route_frame(self):
        with self.lock:
            name = self.selected_route
        path = self.routes_dir / (name + "_waypoints.json")
        if not path.is_file():
            raise RuntimeError("Route file not found: " + str(path))
        with path.open(encoding="utf-8") as f:
            data = json.load(f)
        if data.get("frame") != "map" or data.get("route_key") != name:
            raise RuntimeError("Wrong route or map frame: " + str(path))
        pts = data["waypoints"]
        stride = max(1, math.ceil(len(pts) / 400))
        return {"type": "route", "route": name, "frame": "map",
                "count_full": len(pts), "stride": stride,
                "points": [{"x": p["x"], "y": p["y"]}
                           for p in pts[::stride]]}

    def handle(self, payload):
        """Return an ACK and optional route frame. No arbitrary shell commands."""
        if not isinstance(payload, dict):
            return {"type": "ack", "ok": False, "detail": "JSON object required"}, None
        command = payload.get("command") or payload.get("action")
        response = {"type": "ack", "command": command, "ok": False, "detail": ""}
        extra = None
        try:
            if command == "ping":
                response.update(ok=True, detail="pong")
            elif command in ("route_select", "select_route"):
                name = payload.get("route")
                if name not in ROUTES:
                    raise ValueError("Invalid route")
                with self.lock:
                    if self._running("drive") or self.drive_phase in ("RUNNING", "CHECKING", "START_REQUEST_ACCEPTED", "STOPPING", "GOAL_ARRIVED", "NEEDS_STOP", "STOP_UNVERIFIED"):
                        raise RuntimeError("Cannot change route while DRIVE is active")
                    if self.selected_route != name:
                        self.latest.pop("pose", None)
                    self.selected_route = name
                extra = self._route_frame()
                response.update(ok=True, detail="route selected: " + name)
            elif command == "route":
                extra = self._route_frame()
                response.update(ok=True, detail="route loaded")
            elif command in ("lidar_test_start", "lidar_test_stop"):
                if command == "lidar_test_start":
                    with self.lock:
                        if self._running("ready_start") or self._running("ready_stop") or self.ready_stopping:
                            raise RuntimeError("READY startup/shutdown is in progress; finish it before testing")
                        if (self._running("drive") or self._running("drive_stop")
                                or self._running("drive_reconcile")
                                or self.drive_phase in ("RUNNING", "CHECKING",
                                                       "START_REQUEST_ACCEPTED", "STOPPING",
                                                       "GOAL_ARRIVED", "NEEDS_STOP", "STOP_UNVERIFIED")):
                            raise RuntimeError("DRIVE session must be ended and verified before LiDAR testing")
                        # A completed/failed READY stack is cleaned by pid_lidar_test.sh.
                        # Do not trust the cached ready_state as an active-process test.
                        if self._running("lidar_test_start") or self._running("lidar_test_stop"):
                            raise RuntimeError("LiDAR test command already running")
                    if (self.lidar_test_state / "active").exists():
                        raise RuntimeError("LiDAR test already running")
                    response.update(self._run_job("lidar_test_start", ["pid_lidar_test.sh", "start"]))
                else:
                    if self._running("lidar_test_start"):
                        raise RuntimeError("LiDAR test still starting")
                    response.update(self._run_job("lidar_test_stop", ["pid_lidar_test.sh", "stop"]))
            elif command in ("ready_start", "stack_start"):
                if ((self.lidar_test_state / "active").exists() or
                        self._running("lidar_test_start") or self._running("lidar_test_stop")):
                    raise RuntimeError("LiDAR test active. Press Test End before READY")
                with self.lock:
                    # Never launch READY while shutdown or drive-stop is tearing
                    # down the same ROS / wheel stack.
                    if self.ready_stopping or self._running("ready_stop"):
                        raise RuntimeError("READY shutdown in progress")
                    if self._running("ready_start"):
                        raise RuntimeError("READY already starting")
                    if (self._running("drive") or self._running("drive_stop")
                            or self._running("drive_reconcile")):
                        raise RuntimeError("DRIVE session or verification is still active")
                    # Reject before resetting diagnostics from the active run.
                    self.ready_history.clear()
                    self.ready_failures.clear()
                    self.ready_run_id += 1
                    self.ready_state = "STARTING"
                response.update(self._run_job("ready_start",
                                             ["pid_ready.sh", "start"]))
            elif command == "ready_status":
                response.update(self._run_job("ready_status",
                                             ["pid_ready.sh", "status"]))
            elif command == "ready_stop":
                with self.lock:
                    if self.ready_stopping or self._running("ready_stop"):
                        raise RuntimeError("READY shutdown already requested")
                    if self._running("ready_start") or self._running("drive_stop"):
                        raise RuntimeError("READY startup or DRIVE stop still in progress")
                    if self._running("lidar_test_start") or self._running("lidar_test_stop"):
                        raise RuntimeError("LiDAR test setup or teardown is in progress")
                    self.ready_stopping = True
                    self.ready_state = "STOPPING"
                threading.Thread(target=self._shutdown_ready, daemon=True,
                                 name="ready_shutdown").start()
                response.update(ok=True, detail="READY shutdown requested")
            elif command == "status":
                response.update(ok=True, detail="Current telemetry follows")
                extra = self.telemetry()
            elif command == "drive_reconcile":
                # Read-only reconciliation. Never clear state based solely on a timeout.
                with self.lock:
                    if (self._running("drive") or self._running("drive_stop")
                            or self._running("drive_reconcile")
                            or self._running("ready_start") or self._running("ready_stop")
                            or self.ready_stopping):
                        raise RuntimeError("DRIVE/STOP/READY job still active; cannot verify stopped session")
                    if self.drive_phase in ("CHECKING", "START_REQUEST_ACCEPTED", "RUNNING"):
                        raise RuntimeError("Drive may be active; use DRIVE STOP first")
                response.update(self._run_job("drive_reconcile", ["pid_drive_reconcile.sh", str(self.scripts)]))
            elif command == "drive_start":
                if ((self.lidar_test_state / "active").exists() or
                        self._running("lidar_test_start") or self._running("lidar_test_stop")):
                    raise RuntimeError("LiDAR test active. End test before driving")
                name = payload.get("route", self.selected_route)
                if name not in ROUTES:
                    raise ValueError("Invalid route")
                with self.lock:
                    if self.ready_stopping or self._running("ready_stop"):
                        raise RuntimeError("READY shutdown in progress")
                    if self.estop_latched:
                        raise RuntimeError("E-STOP latched; release separately")
                    if (self._running("drive") or self._running("drive_stop")
                            or self._running("drive_reconcile")
                            or self.drive_phase in ("CHECKING", "START_REQUEST_ACCEPTED", "RUNNING",
                                                    "STOPPING", "GOAL_ARRIVED", "NEEDS_STOP",
                                                    "STOP_UNVERIFIED")):
                        raise RuntimeError("Previous DRIVE session must be stopped and verified before restarting")
                    if self._running("ready_start"):
                        raise RuntimeError("READY still starting")
                    if self.selected_route != name:
                        self.latest.pop("pose", None)
                    self.selected_route = name
                # Check matching map route asset before attempting to move.
                extra = self._route_frame()
                with self.lock:
                    self.active_route = name
                    self.drive_phase = "CHECKING"
                    self.drive_requested_at = time.monotonic()
                    self.drive_accepted = False
                    self.drive_confirmed = False
                    self.drive_rejection_sent = False
                    self.drive_terminal_reason = None
                    self.last_preflight = None
                    self.last_drive_event = None
                try:
                    response.update(self._run_job("drive", ["pid_drive.sh", name],
                                                  long_lived=True))
                except Exception:
                    with self.lock:
                        self.active_route = None
                        self.drive_phase = "FAILED"
                    raise
            elif command in ("drive_stop", "stop", "estop"):
                with self.lock:
                    if command == "estop":
                        self.estop_latched = True
                    if self._running("drive_stop"):
                        raise RuntimeError("Stop already in progress")
                    self.drive_phase = "STOPPING"
                # Abort any still-running preflight/START shell first, to avoid
                # it switching MANUAL back to AUTO after the stop check finishes.
                self._cancel_drive_launch()
                # pid_stop.sh requests MANUAL, controller pause and verifies wheels.
                # Run immediately without waiting for the DRIVE watch process.
                try:
                    response.update(self._run_job("drive_stop", ["pid_stop.sh"]))
                except Exception:
                    with self.lock:
                        self.drive_phase = "STOP_UNVERIFIED"
                    raise
            elif command == "estop_release":
                with self.lock:
                    self.estop_latched = False
                response.update(ok=True, detail="Software E-STOP released; DRIVE not started")
            else:
                response["detail"] = "Unknown command"
        except Exception as exc:
            response["detail"] = str(exc)
            LOG.warning("Rejected %s: %s [drive_phase=%s, ready_state=%s]",
                        command, exc, self.drive_phase, self.ready_state)
        return response, extra

    def _store(self, key, data):
        with self.lock:
            self.latest[key] = (time.monotonic(), data)

    def _json_cb(self, key, msg):
        try:
            value = json.loads(msg.data)
            if isinstance(value, dict):
                stamp = time.monotonic()
                self._store(key, value)
                if key == "drive":
                    self._confirm_drive(value, stamp)
                    state = value.get("state")
                    if state in ("GOAL_REACHED", "MANUAL_INTERVENTION"):
                        self._mark_drive_interrupted(state)
        except (ValueError, TypeError):
            LOG.warning("Invalid %s JSON", key)

    def _pose_cb(self, msg):
        """Publish chair-centre position even before FF-PID starts."""
        if msg.header.frame_id not in ("map", "/map"):
            return
        with self.lock:
            route_name = self.selected_route
        try:
            import tf.transformations as tft
            p, q = msg.pose.pose.position, msg.pose.pose.orientation
            raw_map = tft.quaternion_matrix([q.x, q.y, q.z, q.w])
            raw_map[:3, 3] = (p.x, p.y, p.z)
            corrected = raw_map @ self._chair_centre_transform(route_name)
            x, y = float(corrected[0, 3]), float(corrected[1, 3])
            _, _, yaw = tft.euler_from_matrix(corrected)
            if not all(math.isfinite(v) for v in (x, y, yaw)):
                raise ValueError("Non-finite chair-centre pose")
        except Exception as exc:
            # Do not display raw sensor pose with chair-centred route geometry.
            with self.lock:
                self.latest.pop("pose", None)
                key = (route_name, type(exc).__name__, str(exc))
                if key not in self._pose_transform_errors:
                    self._pose_transform_errors.add(key)
                    LOG.warning("Chair-centre pose unavailable for %s: %s", route_name, exc)
            return
        with self.lock:
            # Route changes may race with this ROS callback.
            if self.selected_route != route_name:
                return
            self.latest["pose"] = (time.monotonic(), {
                "x": x, "y": y, "yaw_deg": math.degrees(yaw),
                "source": "GICP_CHAIR_CENTRE", "frame": "map",
                "reference_point": "chair_centre", "route": route_name,
            })

    def _wheel_cb(self, msg):
        mode = int(msg.data[1]) if len(msg.data) > 1 else None
        self._store("wheel", {"mode": mode,
                              "mode_name": {65: "AUTO", 77: "MANUAL"}.get(mode, "UNKNOWN")})

    def _signal_cb(self, key, msg):
        self._store(key, {"received": True})

    def _diag_cb(self, msg):
        for d in msg.status:
            if d.message:
                self._store("gicp", {"state": d.message,
                                      "level": d.level})
                break

    def attach_ros_when_available(self):
        """Maintain ROS subscriptions across test-owned ROS Master restarts.

        rospy.init_node() may only run once in a process. Re-register the
        subscribers when the master's own PID changes instead of calling
        init_node() again or assuming the old registration survives.
        The temporary LiDAR test master must never become the live READY graph.
        """
        try:
            import rospy
            from std_msgs.msg import String, Int16MultiArray
            from geometry_msgs.msg import PoseWithCovarianceStamped
            from diagnostic_msgs.msg import DiagnosticArray
            from nav_msgs.msg import Odometry
            from sensor_msgs.msg import Imu
            from visualization_msgs.msg import MarkerArray
            from xmlrpc.client import ServerProxy
            from urllib.parse import urlparse
        except ImportError as exc:
            LOG.error("ROS Noetic imports missing: %s", exc)
            return

        initialized = False
        last_master_pid = None
        subscribers = []

        while True:
            try:
                if (self.lidar_test_state / "active").exists():
                    # Even if READY had previously been connected, it must not
                    # receive or register against the test-only ROS graph.
                    for sub in subscribers:
                        sub.unregister()
                    subscribers = []
                    last_master_pid = None
                    self.ros_connected = False
                    time.sleep(1)
                    continue

                uri = os.environ.get("ROS_MASTER_URI", "http://127.0.0.1:11311")
                dest = urlparse(uri)
                with socket.create_connection((dest.hostname, dest.port or 11311), timeout=1):
                    pass
                if (self.lidar_test_state / "active").exists():
                    time.sleep(1)
                    continue

                # ROS Master getPid returns the master process ID, not the
                # Bluetooth bridge's PID. A different PID means a new graph.
                with ServerProxy(uri, allow_none=True) as master:
                    code, message, current_pid = master.getPid("/pid_bt_bridge")
                if code != 1:
                    raise RuntimeError("ROS Master did not return a valid PID: " + str(message))

                if not initialized:
                    rospy.init_node("pid_bt_bridge", anonymous=False, disable_signals=True)
                    initialized = True
                if current_pid != last_master_pid or not subscribers:
                    for sub in subscribers:
                        sub.unregister()
                    subscribers = []
                    # Keep references so they can be unregistered when this
                    # Master disappears. All telemetry is read-only.
                    subscribers.append(rospy.Subscriber("/pid_safety/status", String,
                        lambda msg: self._json_cb("safety", msg), queue_size=2))
                    subscribers.append(rospy.Subscriber("/ff_pid/status", String,
                        lambda msg: self._json_cb("drive", msg), queue_size=2))
                    subscribers.append(rospy.Subscriber("/fast_lio_icp/pose",
                        PoseWithCovarianceStamped, self._pose_cb, queue_size=2))
                    subscribers.append(rospy.Subscriber("/wheel_status",
                        Int16MultiArray, self._wheel_cb, queue_size=2))
                    subscribers.append(rospy.Subscriber(
                        "/fast_lio_icp/localization_diagnostics",
                        DiagnosticArray, self._diag_cb, queue_size=2))
                    for key, topic, typ in (
                        ("lidar", "/livox/lidar", rospy.AnyMsg),
                        ("imu", "/livox/imu", Imu),
                        ("fast_lio", "/Odometry", Odometry),
                        ("perception", "/perception/objects_summary", String),
                        ("exclusion", "/perception/dynamic_boxes", MarkerArray),
                        ("uart", "/uart_tx_diag", String),
                    ):
                        subscribers.append(rospy.Subscriber(
                            topic, typ,
                            lambda msg, k=key: self._signal_cb(k, msg),
                            queue_size=1))
                    last_master_pid = current_pid
                    # Clear old source timestamps after a new graph joins.
                    with self.lock:
                        self.latest.clear()
                    LOG.info("ROS subscribers registered to master PID=%s", current_pid)

                self.rospy = rospy
                self.ros_connected = True
                time.sleep(2)
            except Exception as exc:
                self.ros_connected = False
                last_master_pid = None
                LOG.info("ROS Master unavailable or resubscribe required: %s", exc)
                time.sleep(2)

    def telemetry(self):
        now = time.monotonic()
        with self.lock:
            def fresh(key, ttl):
                value = self.latest.get(key)
                return value[1] if value and now - value[0] <= ttl else None

            def health(key, ttl):
                return "OK" if fresh(key, ttl) is not None else "STALE"

            safety = fresh("safety", 1.5)
            drive = fresh("drive", 1.5)
            pose = fresh("pose", 1.5)
            wheel = fresh("wheel", 2.5)
            gicp = fresh("gicp", 5.0)
            # Use one reference point during READY, DRIVE and after STOP.
            # FF-PID pose_used is already chair-centred but includes its control-
            # loop prediction; do not switch the display between two estimators.
            phase = self.drive_phase
            if phase == "RUNNING" and not drive:
                phase = "STATUS_STALE"
            return {
                "type": "telemetry", "protocol_version": 4, "stamp": time.time(),
                "ros_connected": self.ros_connected and bool(self.rospy),
                "ready": {
                    "state": self.ready_state, "gicp": gicp,
                    "components": {
                        "LiDAR": health("lidar", 2.0), "IMU": health("imu", 2.0),
                        "FAST-LIO": health("fast_lio", 2.0),
                        "Map Pose": health("pose", 2.0),
                        "GICP Diagnostics": health("gicp", 5.0),
                        "Perception": health("perception", 2.5),
                        "GICP Exclusions": health("exclusion", 2.5),
                        "Wheel Status": health("wheel", 2.5),
                        "UART Diagnostics": health("uart", 2.5),
                    },
                    "jobs": {k: dict(v) for k, v in self.job_states.items()},
                    "failures": list(self.ready_failures),
                    "history": list(self.ready_history)[-24:],
                    "run_id": self.ready_run_id,
                },
                "lidar_test": self._lidar_test(),
                "selected_route": self.selected_route,
                "active_route": self.active_route,
                "drive_state": (drive or {}).get("state", "UNKNOWN"),
                "drive_phase": phase,
                "drive_terminal_reason": self.drive_terminal_reason,
                "drive_confirmed": self.drive_confirmed,
                "drive_reconcile_running": self._running("drive_reconcile"),
                "drive": drive, "last_preflight": self.last_preflight,
                "last_drive_event": self.last_drive_event,
                "safety": safety,
                "pose": pose, "wheel": wheel,
                "estop_latched": self.estop_latched,
            }


class Session:
    def __init__(self, sock, bridge):
        self.sock = sock
        self.bridge = bridge
        self.write_lock = threading.Lock()
        self.alive = threading.Event()
        self.alive.set()
        self.event_cursor = bridge.event_seq()

    def send(self, payload):
        try:
            data = (json.dumps(payload, ensure_ascii=False,
                               separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
            with self.write_lock:
                self.sock.sendall(data)
            return True
        except (OSError, ValueError, TypeError):
            self.alive.clear()
            return False

    def serve(self):
        def broadcast():
            while self.alive.is_set():
                for event in self.bridge.events_after(self.event_cursor):
                    if not self.send(event):
                        return
                    self.event_cursor = event["seq"]
                if not self.send(self.bridge.telemetry()):
                    return
                time.sleep(0.5)

        thread = threading.Thread(target=broadcast, daemon=True)
        thread.start()
        buffer = b""
        try:
            while self.alive.is_set():
                chunk = self.sock.recv(4096)
                if not chunk:
                    break
                buffer += chunk
                if len(buffer) > 65536:
                    buffer = b""
                    continue
                while b"\n" in buffer:
                    raw, buffer = buffer.split(b"\n", 1)
                    try:
                        request = json.loads(raw.decode("utf-8"))
                        if not isinstance(request, dict):
                            raise ValueError("Command must be a JSON object")
                        reply, extra = self.bridge.handle(request)
                    except (ValueError, UnicodeError) as exc:
                        reply = {"type": "ack", "ok": False, "detail": str(exc)}
                        extra = None
                    self.send(reply)
                    if extra:
                        self.send(extra)
        except OSError as exc:
            LOG.info("Bluetooth session closed: %s", exc)
        finally:
            self.alive.clear()
            # Never terminate DRIVE/READY jobs on Bluetooth disconnection.
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            thread.join(timeout=2)


def serve_bluez(bridge, uuid, channel):
    import dbus
    import dbus.mainloop.glib
    import dbus.service
    from gi.repository import GLib
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    path = "/uniconlab/wheelchair/pid_spp"

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            LOG.warning("Bluetooth profile released")

        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, properties):
            raw = fd.take()
            client = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                                   socket.BTPROTO_RFCOMM, fileno=raw)
            client.setblocking(True)
            LOG.info("Android connected: %s", device)
            def run():
                try:
                    Session(client, bridge).serve()
                finally:
                    client.close()
                    LOG.info("Android disconnected: %s", device)
            threading.Thread(target=run, daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            LOG.info("Disconnect request: %s", device)

    obj = Profile(bus, path)
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"),
                             "org.bluez.ProfileManager1")
    options = {"Name": "UNICON FF-PID Operator",
               "Role": "server", "Channel": dbus.UInt16(channel),
               "RequireAuthentication": dbus.Boolean(True),
               "RequireAuthorization": dbus.Boolean(False),
               "AutoConnect": dbus.Boolean(False)}
    # No fallback to default SPP: that could hijack the team's Bluetooth profile.
    manager.RegisterProfile(path, uuid.lower(), options)
    LOG.info("PID RFCOMM registered: UUID %s, channel %s", uuid, channel)
    loop = GLib.MainLoop()
    try:
        loop.run()
    except KeyboardInterrupt:
        pass
    finally:
        manager.UnregisterProfile(path)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pid-scripts", default=os.environ.get(
        "PID_SCRIPTS_DIR", "~/wheelchair_localization_src/src/pid/scripts"))
    ap.add_argument("--routes-dir", default=None)
    ap.add_argument("--uuid", default=PID_SPP_UUID)
    ap.add_argument("--channel", type=int, default=PID_SPP_CHANNEL)
    args = ap.parse_args()
    if not 1 <= args.channel <= 30:
        ap.error("RFCOMM channel must be in 1..30")
    scripts = Path(args.pid_scripts).expanduser().resolve()
    # Share the exact route directory used by pid_drive_check.py and FF-PID.
    # With --pid-scripts <REPO_ROOT>/src/pid/scripts, parents[2] is <REPO_ROOT>.
    routes = args.routes_dir or str(scripts.parents[2] / "routes" / "route2_manual_20261031")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [pid_bt] %(message)s")
    bridge = Bridge(scripts, routes)
    threading.Thread(target=bridge.attach_ros_when_available, daemon=True).start()
    return serve_bluez(bridge, args.uuid, args.channel)


if __name__ == "__main__":
    raise SystemExit(main())
