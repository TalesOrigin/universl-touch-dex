package io.universl.touch;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone JNI input bridge. Requires an authorized, platform-specific native backend. */
public final class TouchDex implements AutoCloseable {
    public static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    public static final int OK = 0, ERROR = 1, CLOSED = 2, CANCELLED = 3;

    public interface Callback {
        /** Runs on the responding thread; marshal to your own executor/UI thread if needed. */
        void onResponse(Response response);
    }

    public static final class Response {
        public final long requestId;
        public final int status;
        public final String payload;
        Response(long requestId, int status, String payload) {
            this.requestId = requestId;
            this.status = status;
            this.payload = payload;
        }
        public boolean isSuccess() { return status == OK; }
    }

    // Package-private backend seam also allows JVM tests without Android or JNI.
    interface Backend {
        void send(TouchDex owner, long id, String operation, String text, int[] args);
        void close(TouchDex owner);
    }

    private final Backend backend;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Callback> pending = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private boolean closed;

    /** Load a library name (without lib/.so), e.g. universl_touch. No library is bundled. */
    public TouchDex(String libraryName) {
        if (libraryName == null || libraryName.isEmpty()) throw new IllegalArgumentException("libraryName");
        System.loadLibrary(libraryName);
        backend = new JniBackend();
    }

    TouchDex(Backend backend) { this.backend = backend; }

    /** Backend-specific configuration, typically JSON. Await success before sending inputs. */
    public long init(String config, Callback callback) {
        return submit("init", required(config), callback);
    }
    /** Pointer IDs must remain stable between DOWN and UP/CANCEL. Coordinates are pixels. */
    public long touch(int action, int pointerId, int x, int y, Callback callback) {
        if (action < DOWN || action > CANCEL || pointerId < 0) throw new IllegalArgumentException("action/pointerId");
        return submit("touch", null, callback, action, pointerId, x, y);
    }
    public long input(String text, Callback callback) { return submit("input", required(text), callback); }
    /** Android key codes; action is DOWN or UP. */
    public long key(int action, int keyCode, int metaState, Callback callback) {
        if ((action != DOWN && action != UP) || keyCode < 0) throw new IllegalArgumentException("action/keyCode");
        return submit("key", null, callback, action, keyCode, metaState);
    }
    public long swipe(int x1, int y1, int x2, int y2, int durationMs, Callback callback) {
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs");
        return submit("swipe", null, callback, x1, y1, x2, y2, durationMs);
    }
    /** Exchange two backend-defined input slots. Not an alias for swipe. */
    public long swap(int firstSlot, int secondSlot, Callback callback) {
        if (firstSlot < 0 || secondSlot < 0) throw new IllegalArgumentException("slot");
        return submit("swap", null, callback, firstSlot, secondSlot);
    }
    public long scroll(int x, int y, int horizontal, int vertical, Callback callback) {
        return submit("scroll", null, callback, x, y, horizontal, vertical);
    }
    public long release(Callback callback) { return submit("release", null, callback); }

    private static String required(String value) {
        if (value == null) throw new IllegalArgumentException("text/config must not be null");
        return value;
    }

    private long submit(String operation, String text, Callback callback, int... args) {
        if (callback == null) throw new IllegalArgumentException("callback");
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Bridge is closed");
            long id = nextId.getAndIncrement();
            pending.put(id, callback);
            try {
                backend.send(this, id, operation, text, args);
            } catch (RuntimeException | LinkageError e) {
                onNativeResponse(id, ERROR, e.toString());
            }
            return id;
        }
    }

    /** JNI callback entry point. Unknown, cancelled and duplicate responses are ignored. */
    public void onNativeResponse(long requestId, int status, String payload) {
        Callback callback = pending.remove(requestId);
        if (callback != null) {
            // Never propagate client exceptions into JNI or prevent other pending completions.
            try { callback.onResponse(new Response(requestId, status, payload)); }
            catch (RuntimeException ignored) { }
        }
    }

    /** Cancels Java delivery only; does not interrupt an input already submitted to native code. */
    public void cancel(long requestId) { onNativeResponse(requestId, CANCELLED, "Cancelled"); }

    /** Native close must stop inputs, release held pointers and drain worker threads. */
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
        }
        try {
            backend.close(this);
        } finally {
            for (Long id : pending.keySet()) onNativeResponse(id, CLOSED, "Bridge closed");
        }
    }

    private static final class JniBackend implements Backend {
        public void send(TouchDex owner, long id, String op, String text, int[] a) {
            switch (op) {
                case "init": owner.nativeInit(id, text); break;
                case "touch": owner.nativeTouch(id, a[0], a[1], a[2], a[3]); break;
                case "input": owner.nativeInput(id, text); break;
                case "key": owner.nativeKey(id, a[0], a[1], a[2]); break;
                case "swipe": owner.nativeSwipe(id, a[0], a[1], a[2], a[3], a[4]); break;
                case "swap": owner.nativeSwap(id, a[0], a[1]); break;
                case "scroll": owner.nativeScroll(id, a[0], a[1], a[2], a[3]); break;
                case "release": owner.nativeRelease(id); break;
                default: throw new IllegalArgumentException(op);
            }
        }
        public void close(TouchDex owner) { owner.nativeClose(); }
    }

    private native void nativeInit(long id, String config);
    private native void nativeTouch(long id, int action, int pointerId, int x, int y);
    private native void nativeInput(long id, String text);
    private native void nativeKey(long id, int action, int keyCode, int metaState);
    private native void nativeSwipe(long id, int x1, int y1, int x2, int y2, int durationMs);
    private native void nativeSwap(long id, int firstSlot, int secondSlot);
    private native void nativeScroll(long id, int x, int y, int horizontal, int vertical);
    private native void nativeRelease(long id);
    private native void nativeClose();
}
