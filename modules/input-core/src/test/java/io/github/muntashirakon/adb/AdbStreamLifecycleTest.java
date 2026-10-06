package io.github.muntashirakon.adb;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 使用明确的监视器交接复现 OPEN/EOF 时序，不依赖 sleep 或网络响应快慢。 */
public class AdbStreamLifecycleTest {
    private ServerSocket server;
    private Socket peer;
    private AdbConnection connection;
    private AdbStream stream;

    @Before public void prepare() throws Exception {
        server = new ServerSocket(0);
        PrivateKey unusedKey = new PrivateKey() {
            public String getAlgorithm() { return "RSA"; }
            public String getFormat() { return "PKCS#8"; }
            public byte[] getEncoded() { return new byte[0]; }
            public void destroy() { }
        };
        connection = AdbConnection.create("127.0.0.1", server.getLocalPort(), new KeyPair(unusedKey, null), 26);
        peer = server.accept();
        // 本测试只模拟已建立连接的服务流，不启动 adbd、认证或设备线程。
        field(AdbConnection.class, "mConnectAttempted").set(connection, true);
        field(AdbConnection.class, "mConnectionEstablished").set(connection, true);
        stream = new AdbStream(connection, 1);
    }

    @After public void close() throws Exception {
        if (stream != null) stream.notifyClose(false);
        if (connection != null) connection.close();
        if (peer != null) peer.close();
        if (server != null) server.close();
    }

    @Test(timeout = 3000) public void okayBeforeWaitReturnsImmediately() throws Exception {
        stream.updateRemoteId(27);
        stream.awaitOpen(0, TimeUnit.NANOSECONDS);
        assertFalse(stream.isClosed());
    }

    @Test(timeout = 3000) public void okayAfterWaitWakesOpen() throws Exception {
        CountDownLatch entering = new CountDownLatch(1);
        Running<Void> pending = start(() -> {
            synchronized (stream) {
                entering.countDown();
                stream.awaitOpen(2, TimeUnit.SECONDS);
            }
            return null;
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        // updateRemoteId 必须取得同一把锁，只有等待线程已释放监视器后才能更新。
        stream.updateRemoteId(27);
        pending.result.get(1, TimeUnit.SECONDS);
    }

    @Test(timeout = 3000) public void closeAfterWaitWakesOpen() throws Exception {
        CountDownLatch entering = new CountDownLatch(1);
        Running<Throwable> pending = start(() -> {
            synchronized (stream) {
                entering.countDown();
                try { stream.awaitOpen(2, TimeUnit.SECONDS); return null; }
                catch (IOException error) { return error; }
            }
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        stream.notifyClose(false);
        assertTrue(pending.result.get(1, TimeUnit.SECONDS) instanceof ConnectException);
    }

    @Test(timeout = 3000) public void openWithoutReplyHasDeadline() {
        assertThrows(SocketTimeoutException.class, () -> stream.awaitOpen(0, TimeUnit.NANOSECONDS));
    }

    @Test(timeout = 3000) public void interruptedOpenStopsWaiting() throws Exception {
        CountDownLatch entering = new CountDownLatch(1);
        Running<Throwable> pending = start(() -> {
            synchronized (stream) {
                entering.countDown();
                try { stream.awaitOpen(2, TimeUnit.SECONDS); return null; }
                catch (InterruptedException error) { return error; }
            }
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        pending.thread.interrupt();
        assertTrue(pending.result.get(1, TimeUnit.SECONDS) instanceof InterruptedException);
    }

    @Test(timeout = 3000) public void openTimeoutRemovesHalfOpenStream() throws Exception {
        assertThrows(SocketTimeoutException.class, () -> connection.open("shell:echo fixture", 0, TimeUnit.NANOSECONDS));
        Map<?, ?> opened = (Map<?, ?>) field(AdbConnection.class, "mOpenedStreams").get(connection);
        assertTrue(opened.isEmpty());
    }

    @Test(timeout = 3000) public void connectionFailureKeepsOriginalCause() throws Exception {
        IOException origin = new SocketTimeoutException("fixture");
        field(AdbConnection.class, "mConnectionException").set(connection, origin);
        field(AdbConnection.class, "mConnectionEstablished").set(connection, false);
        field(AdbConnection.class, "mConnectAttempted").set(connection, false);
        Method waiting = AdbConnection.class.getDeclaredMethod("waitForConnection", long.class, TimeUnit.class);
        waiting.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> waiting.invoke(connection, 0L, TimeUnit.NANOSECONDS));
        assertTrue(thrown.getCause() instanceof IOException);
        assertSame(origin, thrown.getCause().getCause());
    }

    @Test(timeout = 3000) public void replyAndPayloadBeforeWaitCanDrainToEof() throws Exception {
        stream.updateRemoteId(27);
        stream.addPayload(new byte[] {1, 2, 3});
        stream.notifyClose(true);
        stream.awaitOpen(0, TimeUnit.NANOSECONDS);
        byte[] bytes = new byte[2];
        assertEquals(2, stream.read(bytes, 0, bytes.length));
        assertArrayEquals(new byte[] {1, 2}, bytes);
        assertEquals(1, stream.read(bytes, 0, bytes.length));
        assertEquals(3, bytes[0]);
        assertEquals(-1, stream.read(bytes, 0, bytes.length));
        assertEquals(-1, stream.read(bytes, 0, bytes.length));
        assertEquals(0, stream.read(bytes, 0, 0));
    }

    @Test(timeout = 3000) public void peerCloseWithoutPayloadReturnsEof() throws Exception {
        stream.notifyClose(true);
        assertEquals(-1, stream.read(new byte[1], 0, 1));
    }

    @Test(timeout = 3000) public void emptyPayloadDoesNotHideFollowingData() throws Exception {
        stream.addPayload(new byte[0]);
        stream.addPayload(new byte[] {7});
        stream.notifyClose(true);
        byte[] result = new byte[1];
        assertEquals(1, stream.read(result, 0, 1));
        assertEquals(7, result[0]);
        assertEquals(-1, stream.read(result, 0, 1));
    }

    @Test(timeout = 3000) public void localCloseKeepsCancellationException() throws Exception {
        stream.addPayload(new byte[] {1});
        stream.close();
        assertThrows(IOException.class, () -> stream.read(new byte[1], 0, 1));
    }

    @Test(timeout = 3000) public void peerCloseWakesBlockedReader() throws Exception {
        Object queue = field(AdbStream.class, "mReadQueue").get(stream);
        CountDownLatch entering = new CountDownLatch(1);
        Running<Integer> pending = start(() -> {
            synchronized (queue) {
                entering.countDown();
                return stream.read(new byte[1], 0, 1);
            }
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        stream.notifyClose(true);
        assertEquals(Integer.valueOf(-1), pending.result.get(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 3000) public void interruptedReadKeepsCauseAndInterruptFlag() throws Exception {
        Object queue = field(AdbStream.class, "mReadQueue").get(stream);
        CountDownLatch entering = new CountDownLatch(1);
        Running<Throwable> pending = start(() -> {
            synchronized (queue) {
                entering.countDown();
                try { stream.read(new byte[1], 0, 1); return null; }
                catch (IOException error) {
                    assertTrue(Thread.currentThread().isInterrupted());
                    return error.getCause();
                }
            }
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        pending.thread.interrupt();
        assertTrue(pending.result.get(1, TimeUnit.SECONDS) instanceof InterruptedException);
    }

    @Test(timeout = 3000) public void pendingPeerCloseRejectsNewWrites() {
        stream.addPayload(new byte[] {1});
        stream.notifyClose(true);
        assertThrows(IOException.class, () -> stream.write(new byte[] {2}, 0, 1));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000) public void transportLossIsVisibleBeforeStreamWakes() throws Exception {
        Map<Integer, AdbStream> opened = (Map<Integer, AdbStream>)
                field(AdbConnection.class, "mOpenedStreams").get(connection);
        opened.put(1, stream);
        Object queue = field(AdbStream.class, "mReadQueue").get(stream);
        CountDownLatch entering = new CountDownLatch(1);
        Running<Boolean> pending = start(() -> {
            synchronized (queue) {
                entering.countDown();
                try { stream.read(new byte[1], 0, 1); }
                catch (IOException expected) { }
                return connection.isConnectionEstablished();
            }
        });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        ((Thread) field(AdbConnection.class, "mConnectionThread").get(connection)).start();
        peer.close();
        assertFalse(pending.result.get(1, TimeUnit.SECONDS));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static <T> Running<T> start(Callable<T> work) {
        FutureTask<T> result = new FutureTask<>(work);
        Thread thread = new Thread(result, "adb-stream-test");
        thread.setDaemon(true);
        thread.start();
        return new Running<>(thread, result);
    }

    private static final class Running<T> {
        final Thread thread;
        final FutureTask<T> result;
        Running(Thread thread, FutureTask<T> result) { this.thread = thread; this.result = result; }
    }
}
