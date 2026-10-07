package app.luoxianlv.input;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import app.luoxianlv.input.IInputCallback;
import app.luoxianlv.input.ScreenshotPacket;

interface IInputService {
    Bundle inspect() = 0;
    void attach(IInputCallback callback) = 1;
    boolean begin(in float[] points, int durationMs, int width, int height, int rotation, long token) = 2;
    void cancel(long token) = 3;
    void release() = 4;
    void heartbeat() = 5;
    void releaseSession(long ticket) = 6;
    ParcelFileDescriptor screenshot(int displayId) = 7;
    ScreenshotPacket screenshotFrame(int displayId, boolean rawOnly) = 8;
    void routingHint(int deviceId, int displayId, String descriptor) = 9;
    void destroy() = 16777114;
}
