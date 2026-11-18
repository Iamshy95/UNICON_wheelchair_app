package com.example.jetsonbtmonitor;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BluetoothWheelchairClient {
    private static final UUID SERIAL_PORT_PROFILE_UUID =
            UUID.fromString("9a1f17cc-18d2-45f0-b6e7-1b0e257a902e");
    private static final int[] DIRECT_RFCOMM_CHANNELS = {1, 2, 3, 4, 5};

    public interface Callback {
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onLineReceived(String line);
        void onError(String message, Throwable throwable);

        /**
         * A reconnect attempt is queued. Default no-op so the device picker,
         * which finishes as soon as it is connected, need not care.
         */
        default void onReconnecting(int attempt, long delayMs) {
        }
    }

    private static BluetoothWheelchairClient sharedClient;

    public static synchronized BluetoothWheelchairClient getSharedInstance(Callback callback) {
        if (sharedClient == null) {
            sharedClient = new BluetoothWheelchairClient(callback);
        } else if (callback != null) {
            sharedClient.setCallback(callback);
        }
        return sharedClient;
    }

    public static synchronized BluetoothWheelchairClient getSharedInstance() {
        return sharedClient;
    }

    private Callback callback;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // The connection reader may block, but outgoing commands must remain FIFO.
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final Object socketLock = new Object();
    private final Object writeLock = new Object();

    private BluetoothSocket socket;
    private volatile boolean running;
    private BluetoothDevice connectedDevice;

    // Reconnect state. The link drops for ordinary reasons in the field -- the
    // operator walks around a corner, or the bridge is restarted on the NUC --
    // and before this the only way back was to leave the dashboard, return to the
    // device picker and connect by hand. That is a bad thing to be doing while
    // watching a chair move.
    private static final long RECONNECT_FIRST_DELAY_MS = 2000L;
    private static final long RECONNECT_MAX_DELAY_MS = 15000L;
    private volatile boolean autoReconnect;
    private volatile BluetoothDevice lastDevice;
    // Reject tasks from an obsolete manual connection/reconnect generation.
    private volatile long connectionGeneration = 0L;
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean(false);

    public BluetoothWheelchairClient(Callback callback) {
        this.callback = callback;
    }

    public synchronized void setCallback(Callback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        synchronized (socketLock) {
            return socket != null && socket.isConnected() && running;
        }
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    public void connect(BluetoothDevice device) {
        connectionGeneration++;
        autoReconnect = false;
        closeLink();
        reconnectInFlight.set(false);
        lastDevice = device;
        autoReconnect = true;
        attempt(device, 0, connectionGeneration);
    }

    /** Launch a connection attempt without allowing an older attempt to take over. */
    private void attempt(BluetoothDevice device, int attemptNumber, long generation) {
        executor.execute(() -> {
            if (!autoReconnect || generation != connectionGeneration) return;
            try {
                BluetoothSocket nextSocket = connectSocket(device, attemptNumber == 0);
                synchronized (socketLock) {
                    if (!autoReconnect || generation != connectionGeneration || socket != null) {
                        closeQuietly(nextSocket);
                        return;
                    }
                    socket = nextSocket;
                    connectedDevice = device;
                    running = true;
                }
                reconnectInFlight.set(false);
                if (callback != null) callback.onConnected(device);
                readLoop(nextSocket, generation);
            } catch (IOException | SecurityException exception) {
                // The failed attempt does not own 'socket'. Never close a newer
                // connection that may have succeeded concurrently.
                if (!autoReconnect || generation != connectionGeneration) return;
                if (attemptNumber == 0 && callback != null) {
                    callback.onError("Bluetooth 연결에 실패했습니다: " + exception.getMessage(), exception);
                }
                reconnectInFlight.set(false);
                scheduleReconnect(attemptNumber + 1, generation);
            }
        });
    }

    /** Retry only for the same explicitly selected connection generation. */
    private void scheduleReconnect(int attemptNumber, long generation) {
        BluetoothDevice device = lastDevice;
        if (!autoReconnect || device == null || generation != connectionGeneration) return;
        if (!reconnectInFlight.compareAndSet(false, true)) return;
        long delay = Math.min(RECONNECT_FIRST_DELAY_MS << Math.min(attemptNumber - 1, 3),
                              RECONNECT_MAX_DELAY_MS);
        if (callback != null) callback.onReconnecting(attemptNumber, delay);
        executor.execute(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reconnectInFlight.set(false);
                return;
            }
            if (!autoReconnect || lastDevice == null || generation != connectionGeneration) {
                reconnectInFlight.set(false);
                return;
            }
            attempt(device, attemptNumber, generation);
        });
    }

    /**
     * SDP lookup first; blind channel probing only as a fallback.
     *
     * The order used to be reversed, and that was not a preference but a
     * correctness bug. {@code createRfcommSocket(channel)} asks for a channel
     * NUMBER, not a service, and the NUC also advertises HSP/HFP
     * ({@code SPP,HSP,AudioSource,AudioSink,Avrcp,HSP_AG}). Probing channels
     * 1..5 therefore attaches to whichever service sits on the first channel
     * that accepts -- observed landing on Hands-Free, which promptly sent
     * {@code AT+VGS=15} while the app cheerfully reported "연결됨".
     *
     * {@code createRfcommSocketToServiceRecord} asks for the SPP UUID by name, so
     * it can only reach the bridge. The bridge registers a real SDP record via
     * org.bluez.Profile1, so this path works; the reflection fallback remains only
     * for a NUC running the older raw-bind transport, which publishes no record.
     */
    /**
     * Force a fresh SDP query before trusting the cached service list.
     *
     * Android caches a remote device's UUIDs from the last SDP fetch. The NUC
     * only advertises SPP while the bridge is running, so any query made while
     * the bridge was down caches a list with no SPP -- and then
     * createRfcommSocketToServiceRecord keeps failing even after the bridge comes
     * back, silently falling through to the channel probe and landing on
     * Hands-Free. Re-fetching is what makes the UUID path usable in practice.
     */
    private void refreshServiceRecords(BluetoothDevice device) {
        try {
            if (hasSerialPortUuid(device)) {
                return;
            }
            device.fetchUuidsWithSdp();
            // The result arrives via an ACTION_UUID broadcast; polling getUuids()
            // is enough here and keeps this class free of a receiver lifecycle.
            for (int i = 0; i < 12; i++) {
                Thread.sleep(250);
                if (hasSerialPortUuid(device)) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT withdrawn; the connect below reports it properly.
        }
    }

    private boolean hasSerialPortUuid(BluetoothDevice device) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) {
                return false;
            }
            for (ParcelUuid uuid : uuids) {
                if (SERIAL_PORT_PROFILE_UUID.equals(uuid.getUuid())) {
                    return true;
                }
            }
        } catch (SecurityException ignored) {
        }
        return false;
    }

    /**
     * @param allowChannelProbe whether to fall back to blind RFCOMM channel
     *     probing when the SPP service record cannot be reached. Only ever true
     *     for a connection the operator asked for. On an automatic retry it is a
     *     trap: while the bridge is down the NUC still advertises Hands-Free, the
     *     probe attaches to it, and the dashboard reports 연결됨 over a socket
     *     that answers with AT+VGS=15. Observed exactly that on 2026-08-23.
     */
    private BluetoothSocket connectSocket(BluetoothDevice device, boolean allowChannelProbe)
            throws IOException {
        refreshServiceRecords(device);
        IOException lastException;
        BluetoothSocket uuidSocket = null;
        try {
            uuidSocket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID);
            uuidSocket.connect();
            return uuidSocket;
        } catch (IOException exception) {
            lastException = exception;
            closeQuietly(uuidSocket);
        }

        // PID has its own RFCOMM service UUID. Never probe raw channel numbers:
        // the original team bridge and the NUC hands-free profiles may be running.
        throw lastException;
    }

    public void sendJsonCommand(JSONObject json) {
        if (json != null) {
            sendLine(json.toString());
        }
    }

    public void sendLine(String line) {
        // Capture the current connection at submission time. A delayed command
        // must NOT be replayed into a later Bluetooth session.
        final BluetoothSocket submittedSocket;
        synchronized (socketLock) {
            submittedSocket = socket;
        }
        commandExecutor.execute(() -> {
            synchronized (socketLock) {
                if (submittedSocket == null || socket != submittedSocket ||
                        !running || !submittedSocket.isConnected()) {
                    if (callback != null) callback.onError(
                            "명령을 전송하지 못했습니다. Bluetooth 연결 상태를 확인하세요.", null);
                    return;
                }
            }
            try {
                synchronized (writeLock) {
                    // Ordered command sends; re-check identity immediately
                    // before writing because reconnect may have occurred.
                    synchronized (socketLock) {
                        if (socket != submittedSocket || !running) return;
                    }
                    OutputStream outputStream = submittedSocket.getOutputStream();
                    outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (IOException exception) {
                if (callback != null) callback.onError("Bluetooth 명령 전송 실패", exception);
                // Close only the socket that failed. A stale writer cannot
                // disconnect a newer connection established during the write.
                closeIfCurrent(submittedSocket);
            }
        });
    }

    /** Close only the current socket; its reader schedules reconnection. */
    public void forceReconnect() {
        BluetoothSocket old;
        synchronized (socketLock) {
            old = socket;
        }
        closeQuietly(old);
    }

    /** Explicit disconnect: no automatic retry and no stale callback takeover. */
    public void disconnect() {
        connectionGeneration++;
        autoReconnect = false;
        lastDevice = null;
        reconnectInFlight.set(false);
        closeLink();
    }

    private void closeLink() {
        running = false;
        closeCurrentSocket();
        connectedDevice = null;
    }

    public void shutdown() {
        disconnect();
        commandExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private void readLoop(BluetoothSocket activeSocket, long generation) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && isCurrentSocket(activeSocket)
                    && (line = reader.readLine()) != null) {
                if (!line.trim().isEmpty() && isCurrentSocket(activeSocket)) {
                    if (callback != null) callback.onLineReceived(line.trim());
                }
            }
        } catch (IOException exception) {
            if (running && isCurrentSocket(activeSocket) && callback != null) {
                callback.onError("Bluetooth 수신 중 연결이 끊겼습니다.", exception);
            }
        } finally {
            boolean owned;
            synchronized (socketLock) {
                owned = socket == activeSocket && generation == connectionGeneration;
                if (owned) {
                    socket = null;
                    running = false;
                    connectedDevice = null;
                }
            }
            closeQuietly(activeSocket);
            if (owned) {
                if (callback != null) callback.onDisconnected();
                scheduleReconnect(1, generation);
            }
        }
    }

    private boolean isCurrentSocket(BluetoothSocket candidate) {
        synchronized (socketLock) {
            return socket == candidate;
        }
    }

    private void closeIfCurrent(BluetoothSocket candidate) {
        synchronized (socketLock) {
            if (socket != candidate) return;
        }
        // The owning reader clears the shared socket and schedules reconnect.
        closeQuietly(candidate);
    }

    private void closeCurrentSocket() {
        BluetoothSocket old;
        synchronized (socketLock) {
            old = socket;
            socket = null;
        }
        closeQuietly(old);
    }

    private void closeQuietly(BluetoothSocket socketToClose) {
        if (socketToClose == null) {
            return;
        }
        try {
            socketToClose.close();
        } catch (IOException ignored) {
        }
    }
}
