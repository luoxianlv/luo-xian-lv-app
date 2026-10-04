package app.luoxianlv.update;

import java.io.File;
import java.io.IOException;

@FunctionalInterface
public interface PatchMerger {
  void merge(File base, File patch, File output, long targetSize, Cancellation cancellation) throws IOException;
}
