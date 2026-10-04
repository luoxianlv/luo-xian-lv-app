package app.luoxianlv.update;
import android.os.ParcelFileDescriptor;
interface IDeltaWorker {
    int merge(in ParcelFileDescriptor base, in ParcelFileDescriptor patch,
        in ParcelFileDescriptor output, long targetSize, long timeoutMillis, long jobToken);
    oneway void cancel(long jobToken);
}
