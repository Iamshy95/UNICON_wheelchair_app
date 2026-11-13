package com.example.jetsonbtmonitor;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;

/** Offline protocol tests: no Android, Bluetooth, ROS, motor, or physical IO. */
public class IndoorDriveSimulatorTest {
    private static JSONObject find(List<JSONObject> frames, String type) {
        for (JSONObject frame : frames) if (type.equals(frame.optString("type"))) return frame;
        return null;
    }

    @Test public void normalDriveAndStop() throws Exception {
        IndoorDriveSimulator sim = new IndoorDriveSimulator();
        assertEquals("IDLE", sim.phase());
        assertTrue(find(sim.handle("drive_start", "A_to_D5"), "ack").getBoolean("ok"));
        assertEquals("RUNNING", sim.phase());
        assertTrue(find(sim.handle("drive_stop", null), "ack").getBoolean("ok"));
        assertEquals("STOPPED", sim.phase());
    }

    @Test public void staleSessionRequiresVerifiedRecovery() throws Exception {
        IndoorDriveSimulator sim = new IndoorDriveSimulator();
        sim.scenario("stale");
        assertFalse(find(sim.handle("drive_start", "A_to_D5"), "ack").getBoolean("ok"));
        assertEquals("STOP_UNVERIFIED", sim.phase());
        assertTrue(find(sim.handle("drive_reconcile", null), "job_result").getBoolean("ok"));
        assertEquals("STOPPED", sim.phase());
        assertTrue(find(sim.handle("drive_start", "A_to_D5"), "ack").getBoolean("ok"));
    }

    @Test public void unknownWheelsMustNotClearStop() throws Exception {
        IndoorDriveSimulator sim = new IndoorDriveSimulator();
        sim.scenario("wheel_unknown");
        assertFalse(find(sim.handle("drive_reconcile", null), "job_result").getBoolean("ok"));
        assertEquals("STOP_UNVERIFIED", sim.phase());
    }

    @Test public void lostStopResultStaysUnverified() throws Exception {
        IndoorDriveSimulator sim = new IndoorDriveSimulator();
        sim.scenario("no_result");
        List<JSONObject> frames = sim.handle("drive_stop", null);
        assertEquals("STOPPING", sim.phase());
        assertNull(find(frames, "job_result"));
        assertFalse(find(sim.handle("drive_start", "A_to_D5"), "ack").getBoolean("ok"));
    }

    @Test public void failedStopCanBeRetried() throws Exception {
        IndoorDriveSimulator sim = new IndoorDriveSimulator();
        sim.scenario("stop_failed");
        assertFalse(find(sim.handle("drive_stop", null), "job_result").getBoolean("ok"));
        assertEquals("STOP_UNVERIFIED", sim.phase());
        assertTrue(find(sim.handle("drive_stop", null), "job_result").getBoolean("ok"));
        assertEquals("STOPPED", sim.phase());
    }
}
