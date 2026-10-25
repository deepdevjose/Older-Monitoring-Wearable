package org.caremonitor.demo;

/** Shared contract for user-selected BLE and Classic Bluetooth sensor links. */
public interface SensorTransport {
    interface Listener {
        /** scanning, scan_stopped, connecting, discovering, subscribing, ready, disconnected, closed. */
        void onState(String state);
        /** Integer.MIN_VALUE means that an RSSI measurement is unavailable. */
        void onDevice(String address, String name, int rssi);
        /** One complete, bounded UTF-8 record, without its LF/CRLF terminator. */
        void onJsonLine(String line);
        void onError(String message);
    }

    void startScan();
    void stopScan();
    void connect(String address);
    void disconnect();
    void close();
    boolean isReady();
}
