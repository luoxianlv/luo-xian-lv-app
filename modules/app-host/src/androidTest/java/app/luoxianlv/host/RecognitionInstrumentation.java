package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** 用真实宿主的独立业务加载器测量 Bitmap 识别，不给薄宿主加入 Kotlin 依赖。 */
public final class RecognitionInstrumentation extends Instrumentation {
  private static final String[] MODES = {"SEMITONE", "RAISE", "NATURAL", "LOWER"};

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle output = new Bundle();
    Activity main = null;
    boolean success = false;
    try {
      var context = getTargetContext();
      require(context.getPackageName().endsWith(".debug"), "识别验收仅允许 Debug APP");
      File external = context.getExternalFilesDir(null);
      require(external != null, "外部测试目录不可用");
      File directory = new File(external, "recognition-benchmark");
      File[] images = directory.listFiles(file -> {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg"));
      });
      require(images != null && images.length > 0 && images.length <= 64, "测试 JPEG 缺失或数量超限");
      Arrays.sort(images, (left, right) -> left.getName().compareTo(right.getName()));
      main = startActivitySync(new Intent()
          .setClassName(context, "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 60000;
      Bootstrap.Source source;
      while (true) {
        try {
          source = Bootstrap.source();
          require(source.prepared != null, "宿主业务来源未准备");
          break;
        } catch (IllegalStateException unavailable) {
          require(SystemClock.elapsedRealtime() < deadline, "等待真实宿主业务超时");
          SystemClock.sleep(50);
        }
      }
      ClassLoader loader = source.prepared.classLoader();
      require(loader != context.getClassLoader(), "识别业务没有独立类加载器");
      Class<?> recognizer = Class.forName("app.luoxianlv.recognition.ScreenRecognizer", true, loader);
      Class<?> resultType = Class.forName("app.luoxianlv.recognition.ScreenRecognizer$Result", true, loader);
      require(recognizer.getClassLoader() == loader && resultType.getClassLoader() == loader,
          "识别实现混入宿主或共享运行时");
      Object engine = recognizer.getField("INSTANCE").get(null);
      Method fromBitmap = recognizer.getMethod("fromBitmap", Bitmap.class, resultType);
      JSONArray entries = new JSONArray();
      ArrayList<Double> fullTimes = new ArrayList<>();
      ArrayList<Double> hintTimes = new ArrayList<>();
      ArrayList<Double> verifiedTimes = new ArrayList<>();
      for (File image : images) {
        require(image.length() > 0 && image.length() <= 16L * 1024 * 1024, "测试图像为空或过大");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
        require(bitmap != null, "无法解码测试图像：" + image.getName());
        try {
          require(bitmap.getWidth() <= 8192 && bitmap.getHeight() <= 8192, "测试图像尺寸超限");
          Object previous = fromBitmap.invoke(engine, bitmap, null);
          Snapshot expected = Snapshot.read(previous);
          int analysisWidth = Math.min(1024, bitmap.getWidth());
          int analysisHeight = bitmap.getWidth() > 1024
              ? Math.max(1, (int) (bitmap.getHeight() * (1024f / bitmap.getWidth())))
              : bitmap.getHeight();
          for (int warmup = 0; warmup < 2; warmup++) {
            expected.compare(Snapshot.read(fromBitmap.invoke(engine, bitmap, null)), 0f, 0f);
            expected.compare(Snapshot.read(fromBitmap.invoke(engine, bitmap, previous)),
                2f / analysisWidth, 2f / analysisHeight);
          }
          long[] full = new long[3];
          long[] hint = new long[3];
          Snapshot checked = null;
          for (int sample = 0; sample < 3; sample++) {
            long began = SystemClock.elapsedRealtimeNanos();
            Object fullResult = fromBitmap.invoke(engine, bitmap, null);
            full[sample] = SystemClock.elapsedRealtimeNanos() - began;
            expected.compare(Snapshot.read(fullResult), 0f, 0f);
            began = SystemClock.elapsedRealtimeNanos();
            Object hintResult = fromBitmap.invoke(engine, bitmap, previous);
            hint[sample] = SystemClock.elapsedRealtimeNanos() - began;
            checked = Snapshot.read(hintResult);
            expected.compare(checked, 2f / analysisWidth, 2f / analysisHeight);
          }
          require(Bootstrap.source() == source, "验收期间业务代际发生变化");
          double fullMs = median(full) / 1_000_000.0;
          double hintMs = median(hint) / 1_000_000.0;
          fullTimes.add(fullMs);
          hintTimes.add(hintMs);
          if (checked.reused) verifiedTimes.add(hintMs);
          JSONObject entry = new JSONObject();
          entry.put("file", image.getName());
          entry.put("width", bitmap.getWidth());
          entry.put("height", bitmap.getHeight());
          entry.put("fullMedianMs", fullMs);
          entry.put("hintMedianMs", hintMs);
          entry.put("verifiedGeometry", checked.reused);
          entry.put("fullResult", expected.json());
          entry.put("hintResult", checked.json());
          entries.put(entry);
          step(String.format(Locale.ROOT, "%s：完整 %.3f 毫秒，复核候选 %.3f 毫秒，十二边框复核=%s",
              image.getName(), fullMs, hintMs, checked.reused ? "通过" : "转完整识别"));
        } finally {
          bitmap.recycle();
        }
      }
      JSONObject report = new JSONObject();
      report.put("passed", true);
      report.put("device", Build.MODEL);
      report.put("api", Build.VERSION.SDK_INT);
      report.put("images", entries);
      report.put("warmups", 2);
      report.put("samples", 3);
      report.put("fullMedianMs", median(fullTimes));
      report.put("hintMedianMs", median(hintTimes));
      report.put("verifiedGeometryCount", verifiedTimes.size());
      report.put("verifiedGeometryMedianMs", verifiedTimes.isEmpty() ? JSONObject.NULL : median(verifiedTimes));
      report.put("measurement", "真实业务加载器执行 Bitmap 缩放、灰度转换和识别；不含 JPEG 解码或系统截图");
      Files.write(new File(directory, "recognition-benchmark-report.json").toPath(),
          report.toString(2).getBytes(StandardCharsets.UTF_8));
      output.putString("stream", String.format(Locale.ROOT,
          "通过：%d 张截图的完整识别与候选复核，完整中位 %.3f 毫秒，候选中位 %.3f 毫秒，十二框快路 %d 张。报告为 UTF-8。\n",
          images.length, median(fullTimes), median(hintTimes), verifiedTimes.size()));
      output.putBoolean("passed", true);
      success = true;
    } catch (Throwable failure) {
      StringWriter trace = new StringWriter();
      failure.printStackTrace(new PrintWriter(trace));
      output.putBoolean("passed", false);
      output.putString("stream", "识别验收失败：\n" + trace);
    } finally {
      if (main != null) {
        Activity activity = main;
        runOnMainSync(activity::finish);
      }
    }
    finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
  }

  private static final class Snapshot {
    float[] notes;
    float noteY;
    Map<String, float[]> modes;
    String selected;
    Boolean halfTone;
    boolean reused;
    int noteBorders, modeBorders;

    static Snapshot read(Object result) throws Exception {
      require(result != null, "识别结果为空");
      Snapshot value = new Snapshot();
      Object layout = getter(result, "getLayout");
      value.notes = ((float[]) getter(layout, "getNoteX")).clone();
      value.noteY = ((Number) getter(layout, "getNoteY")).floatValue();
      require(value.notes.length == 8 && unit(value.noteY), "音符布局无效");
      for (int i = 0; i < value.notes.length; i++) {
        require(unit(value.notes[i]) && (i == 0 || value.notes[i] > value.notes[i - 1]),
            "音符横坐标越界或次序错误");
      }
      value.modes = new LinkedHashMap<>();
      Map<?, ?> modes = (Map<?, ?>) getter(layout, "getModes");
      require(modes.size() == 4, "音区布局不是四个按钮");
      for (var entry : modes.entrySet()) {
        float[] point = ((float[]) entry.getValue()).clone();
        require(point.length == 2 && unit(point[0]) && unit(point[1]) && point[1] < value.noteY,
            "音区坐标越界或与音符行次序错误");
        value.modes.put(((Enum<?>) entry.getKey()).name(), point);
      }
      for (String mode : MODES) require(value.modes.containsKey(mode), "缺少音区布局：" + mode);
      Object selected = getter(result, "getMode");
      value.selected = selected == null ? null : ((Enum<?>) selected).name();
      value.halfTone = (Boolean) getter(result, "getHalfTone");
      value.reused = (Boolean) getter(result, "getReusedGeometry");
      value.noteBorders = ((Number) getter(result, "getNoteBorders")).intValue();
      value.modeBorders = ((Number) getter(result, "getModeBorders")).intValue();
      require(value.noteBorders >= 0 && value.noteBorders <= 8 && value.modeBorders >= 0 && value.modeBorders <= 4,
          "圆框数量越界");
      require(!value.reused || value.noteBorders == 8 && value.modeBorders == 4,
          "快路没有当前十二个圆框证据");
      return value;
    }

    void compare(Snapshot actual, float toleranceX, float toleranceY) {
      for (int i = 0; i < notes.length; i++) {
        require(Math.abs(notes[i] - actual.notes[i]) <= toleranceX + .000001f, "音符定位不一致");
      }
      require(Math.abs(noteY - actual.noteY) <= toleranceY + .000001f, "音符行高不一致");
      for (String mode : MODES) {
        float[] expected = modes.get(mode);
        float[] point = actual.modes.get(mode);
        require(Math.abs(expected[0] - point[0]) <= toleranceX + .000001f
            && Math.abs(expected[1] - point[1]) <= toleranceY + .000001f, "音区定位不一致：" + mode);
      }
      require(java.util.Objects.equals(selected, actual.selected)
          && java.util.Objects.equals(halfTone, actual.halfTone), "当前音区或半音状态不一致");
      if (!actual.reused) require(noteBorders == actual.noteBorders && modeBorders == actual.modeBorders,
          "重复完整识别的圆框结果不一致");
    }

    JSONObject json() throws Exception {
      JSONObject output = new JSONObject();
      JSONArray notePoints = new JSONArray();
      for (float x : notes) notePoints.put(x);
      output.put("noteX", notePoints);
      output.put("noteY", noteY);
      JSONObject modePoints = new JSONObject();
      for (String mode : MODES) modePoints.put(mode, new JSONArray(modes.get(mode)));
      output.put("modes", modePoints);
      output.put("selected", selected == null ? JSONObject.NULL : selected);
      output.put("halfTone", halfTone == null ? JSONObject.NULL : halfTone);
      output.put("noteBorders", noteBorders);
      output.put("modeBorders", modeBorders);
      return output;
    }
  }

  private static Object getter(Object target, String name) throws Exception {
    return target.getClass().getMethod(name).invoke(target);
  }

  private static boolean unit(float value) {
    return Float.isFinite(value) && value > 0f && value < 1f;
  }

  private static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static double median(ArrayList<Double> values) {
    Double[] sorted = values.toArray(new Double[0]);
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private void step(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    sendStatus(0, status);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
