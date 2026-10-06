package app.luoxianlv.input;

import android.os.Bundle;

oneway interface IInputCallback {
    void changed(in Bundle state);
    void finished(long token, boolean success, String message);
}
