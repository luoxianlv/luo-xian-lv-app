package app.luoxianlv;

import android.os.SystemClock;
import app.luoxianlv.core.score.ScoreParser;
import app.luoxianlv.core.score.ScoreWork;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.coroutines.EmptyCoroutineContext;

/** Android ICU 实测；使用 Framework 调用，避免测试专用 Kotlin 扩展被分发包 R8 裁剪。 */
public final class ScorePerformanceChecks {
    public static String run() throws Exception {
        String text = "1:0.5 ".repeat(2000);
        Pattern pattern = Pattern.compile("tempo=\\d+(?:\\.\\d+)?|unit=\\d+(?:\\.\\d+)?|:[0-9]+(?:\\.[0-9]+)?|rest|[0-8iIrR休]|[\\[\\]()#+'bB,~|.·-]", Pattern.CASE_INSENSITIVE);
        ScoreParser.INSTANCE.parse("1 2 3");
        long oldStart = SystemClock.elapsedRealtime();
        ArrayList<Matcher> matches = new ArrayList<>();
        // 对照旧 MatchResult.next + toList：每个 token 重建并保留一个 Matcher。
        int offset = 0;
        while (offset < text.length()) {
            Matcher match = pattern.matcher(text);
            if (!match.find(offset)) break;
            matches.add(match);
            offset = match.end();
        }
        if (matches.size() != 4000) throw new AssertionError("旧分词结果错误");
        long oldMs = SystemClock.elapsedRealtime() - oldStart;
        matches.clear();
        long start = SystemClock.elapsedRealtime();
        if (ScoreParser.INSTANCE.parse(text).size() != 2000) throw new AssertionError("分词语义改变");
        long newMs = SystemClock.elapsedRealtime() - start;
        long largeStart = SystemClock.elapsedRealtime();
        if (ScoreParser.INSTANCE.parse("1:0.5 ".repeat(100000)).size() != 100000) throw new AssertionError("长谱数量错误");
        long largeMs = SystemClock.elapsedRealtime() - largeStart;
        if (largeMs > 10000) throw new AssertionError("长谱异常耗时：" + largeMs);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        try {
            ScoreWork.INSTANCE.getPreview().dispatch(EmptyCoroutineContext.INSTANCE, () -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("预览队列未启动");
            ScoreWork.INSTANCE.getPlayback().dispatch(EmptyCoroutineContext.INSTANCE, () -> {
                if (ScoreParser.INSTANCE.parse(text).size() == 2000) done.countDown();
            });
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("播放被列表队列阻塞");
        } finally { release.countDown(); }
        return "2000 音符：旧分词 " + oldMs + "ms，新完整解析 " + newMs + "ms；100000 音符 " + largeMs + "ms；播放队列独立通过";
    }
}
