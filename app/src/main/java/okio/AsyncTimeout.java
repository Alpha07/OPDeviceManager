package okio;

import java.io.IOException;
import java.io.InterruptedIOException;

public class AsyncTimeout extends Timeout {
    private static AsyncTimeout head;
    private boolean inQueue;
    private AsyncTimeout next;
    private long timeoutAt;

    /* renamed from: okio.AsyncTimeout.1 */
    class AnonymousClass1 implements Sink {
        final /* synthetic */ Sink val$sink;

        AnonymousClass1(Sink sink) {
            this.val$sink = sink;
        }

        public void write(Buffer source, long byteCount) throws IOException {
            AsyncTimeout.this.enter();
            try {
                this.val$sink.write(source, byteCount);
                AsyncTimeout.this.exit(true);
            } catch (IOException e) {
                throw AsyncTimeout.this.exit(e);
            } catch (Throwable th) {
                AsyncTimeout.this.exit(false);
            }
        }

        public void flush() throws IOException {
            AsyncTimeout.this.enter();
            try {
                this.val$sink.flush();
                AsyncTimeout.this.exit(true);
            } catch (IOException e) {
                throw AsyncTimeout.this.exit(e);
            } catch (Throwable th) {
                AsyncTimeout.this.exit(false);
            }
        }

        public void close() throws IOException {
            AsyncTimeout.this.enter();
            try {
                this.val$sink.close();
                AsyncTimeout.this.exit(true);
            } catch (IOException e) {
                throw AsyncTimeout.this.exit(e);
            } catch (Throwable th) {
                AsyncTimeout.this.exit(false);
            }
        }

        public Timeout timeout() {
            return AsyncTimeout.this;
        }

        public String toString() {
            return "AsyncTimeout.sink(" + this.val$sink + ")";
        }
    }

    /* renamed from: okio.AsyncTimeout.2 */
    class AnonymousClass2 implements Source {
        final /* synthetic */ Source val$source;

        AnonymousClass2(Source source) {
            this.val$source = source;
        }

        public long read(Buffer sink, long byteCount) throws IOException {
            AsyncTimeout.this.enter();
            try {
                long result = this.val$source.read(sink, byteCount);
                AsyncTimeout.this.exit(true);
                return result;
            } catch (IOException e) {
                throw AsyncTimeout.this.exit(e);
            } catch (Throwable th) {
                AsyncTimeout.this.exit(false);
            }
        }

        public void close() throws IOException {
            try {
                this.val$source.close();
                AsyncTimeout.this.exit(true);
            } catch (IOException e) {
                throw AsyncTimeout.this.exit(e);
            } catch (Throwable th) {
                AsyncTimeout.this.exit(false);
            }
        }

        public Timeout timeout() {
            return AsyncTimeout.this;
        }

        public String toString() {
            return "AsyncTimeout.source(" + this.val$source + ")";
        }
    }

    private static final class Watchdog extends Thread {
        public Watchdog() {
            super("Okio Watchdog");
            setDaemon(true);
        }

        public void run() {
            while (true) {
                try {
                    AsyncTimeout timedOut = AsyncTimeout.awaitTimeout();
                    if (timedOut != null) {
                        timedOut.timedOut();
                    }
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public final void enter() {
        if (this.inQueue) {
            throw new IllegalStateException("Unbalanced enter/exit");
        }
        long timeoutNanos = timeoutNanos();
        boolean hasDeadline = hasDeadline();
        if (timeoutNanos != 0 || hasDeadline) {
            this.inQueue = true;
            scheduleTimeout(this, timeoutNanos, hasDeadline);
        }
    }

    private static synchronized void scheduleTimeout(AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
        synchronized (AsyncTimeout.class) {
            if (head == null) {
                head = new AsyncTimeout();
                new Watchdog().start();
            }
            long now = System.nanoTime();
            if (timeoutNanos != 0 && hasDeadline) {
                node.timeoutAt = Math.min(timeoutNanos, node.deadlineNanoTime() - now) + now;
            } else if (timeoutNanos != 0) {
                node.timeoutAt = now + timeoutNanos;
            } else if (hasDeadline) {
                node.timeoutAt = node.deadlineNanoTime();
            } else {
                throw new AssertionError();
            }
            long remainingNanos = node.remainingNanos(now);
            AsyncTimeout prev = head;
            while (prev.next != null) {
                Object obj;
                if (remainingNanos >= prev.next.remainingNanos(now)) {
                    obj = 1;
                } else {
                    obj = null;
                }
                if (obj == null) {
                    break;
                }
                prev = prev.next;
            }
            node.next = prev.next;
            prev.next = node;
            if (prev == head) {
                AsyncTimeout.class.notify();
            }
        }
    }

    public final boolean exit() {
        if (!this.inQueue) {
            return false;
        }
        this.inQueue = false;
        return cancelScheduledTimeout(this);
    }

    private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
        synchronized (AsyncTimeout.class) {
            AsyncTimeout prev = head;
            while (prev != null) {
                if (prev.next != node) {
                    prev = prev.next;
                } else {
                    prev.next = node.next;
                    node.next = null;
                    return false;
                }
            }
            return true;
        }
    }

    private long remainingNanos(long now) {
        return this.timeoutAt - now;
    }

    protected void timedOut() {
    }

    public final Sink sink(Sink sink) {
        return new AnonymousClass1(sink);
    }

    public final Source source(Source source) {
        return new AnonymousClass2(source);
    }

    final void exit(boolean throwOnTimeout) throws IOException {
        if (exit() && throwOnTimeout) {
            throw new InterruptedIOException("timeout");
        }
    }

    final IOException exit(IOException cause) throws IOException {
        if (!exit()) {
            return cause;
        }
        InterruptedIOException e = new InterruptedIOException("timeout");
        e.initCause(cause);
        return e;
    }

    private static synchronized AsyncTimeout awaitTimeout() throws InterruptedException {
        synchronized (AsyncTimeout.class) {
            AsyncTimeout node = head.next;
            if (node != null) {
                long waitNanos = node.remainingNanos(System.nanoTime());
                if ((waitNanos <= 0 ? 1 : null) == null) {
                    long waitMillis = waitNanos / 1000000;
                    AsyncTimeout.class.wait(waitMillis, (int) (waitNanos - (waitMillis * 1000000)));
                    return null;
                }
                head.next = node.next;
                node.next = null;
                return node;
            }
            AsyncTimeout.class.wait();
            return null;
        }
    }
}
