package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.os.Process;
import android.util.SparseArray;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL_DEFAULT_US = 1_000_000 / 60;
    // Busy-wait the final fraction of a ms for sub-ms pacing precision (mirrors DXVK's
    // frame limiter, which sleeps most of the interval then spins the tail).
    private static final long BUSY_WAIT_THRESHOLD_NS = 500_000L; // 500us
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    // FPS limiter: paces the game's render loop via delayed PresentIdleNotify /
    // PresentCompleteNotify back-pressure. Without this the game ignores any Android-side
    // display throttle and renders at full speed regardless.
    //
    // Pacing runs on a dedicated high-priority thread that parkNanos + busy-waits to a
    // precise target time, rather than via ScheduledExecutorService. This matters because
    // UE/Unity frame-rate smoothers latch onto the observed Present cadence — feeding them
    // a jittery or late cadence wedges them into the wrong target frame rate, and they
    // don't recover even after the cap changes. We also report the *actual* fire time as
    // ust (not the planned time) so the cadence the client measures matches the cadence
    // we're actually delivering.
    private volatile long targetIntervalUs = 0L;
    // Incremented on every setFrameRateLimit call so the pacing thread can detect that
    // the limit changed and either fire immediately or re-pace at the new rate.
    private final AtomicLong limitGeneration = new AtomicLong(0L);
    private final LinkedBlockingQueue<PendingPresent> pacingQueue = new LinkedBlockingQueue<>();
    private final Thread pacingThread;
    private volatile boolean pacingShutdown = false;

    public PresentExtension() {
        pacingThread = new Thread(this::pacingLoop, "PresentExt-FpsLimiter");
        pacingThread.setDaemon(true);
        pacingThread.start();
    }

    public void setFrameRateLimit(int limit) {
        int sanitized = Math.max(0, limit);
        targetIntervalUs = sanitized > 0 ? 1_000_000L / sanitized : 0L;
        limitGeneration.incrementAndGet();
        // Wake the pacing thread so any in-flight park reconsiders the new limit immediately
        // instead of stalling the game at the old cadence.
        LockSupport.unpark(pacingThread);
    }

    public void close() {
        pacingShutdown = true;
        pacingThread.interrupt();
    }

    private static class PendingPresent {
        final Window window;
        final Pixmap pixmap;
        final int serial;
        final int idleFence;
        final long enqueueUst;
        final long generation;

        PendingPresent(Window window, Pixmap pixmap, int serial, int idleFence,
                       long enqueueUst, long generation) {
            this.window = window;
            this.pixmap = pixmap;
            this.serial = serial;
            this.idleFence = idleFence;
            this.enqueueUst = enqueueUst;
            this.generation = generation;
        }
    }

    private void pacingLoop() {
        try {
            // URGENT_DISPLAY (-8) — the same priority Android uses for SurfaceFlinger.
            // Reduces scheduler jitter that would otherwise show up as bad frame pacing.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
            // Not available in plain-JVM unit tests.
        }

        long lastFireUst = 0L;
        // Monotonic msc — strictly +1 per fire. Avoids the duplicate-MSC pathology that
        // the old "msc = ust / 16666us" formula produced at caps above 60Hz (when
        // targetIntervalUs < FAKE_INTERVAL_DEFAULT_US, consecutive frames could land in
        // the same 16ms bucket and report identical msc, confusing WSI clients).
        long mscCounter = 0L;

        while (!pacingShutdown) {
            PendingPresent p;
            try {
                p = pacingQueue.take();
            } catch (InterruptedException e) {
                if (pacingShutdown) return;
                continue;
            }

            long interval = targetIntervalUs;
            long generationNow = limitGeneration.get();
            long nowUst = System.nanoTime() / 1000L;

            long fireUst;
            if (interval <= 0L || p.generation != generationNow) {
                // Limit went away (or changed while this present was queued) — fire
                // immediately so the game is not stalled at the old cadence.
                fireUst = nowUst;
            } else {
                long target = Math.max(lastFireUst + interval, p.enqueueUst);
                long delayNs = (target - nowUst) * 1_000L;
                if (delayNs > BUSY_WAIT_THRESHOLD_NS) {
                    LockSupport.parkNanos(delayNs - BUSY_WAIT_THRESHOLD_NS);
                }
                // Busy-wait the final fraction for sub-ms precision. Bail out cheaply if
                // the limit changes (unpark) or we're shutting down.
                long targetNs = target * 1_000L;
                while (System.nanoTime() < targetNs) {
                    if (limitGeneration.get() != generationNow) break;
                    if (pacingShutdown) return;
                }
                fireUst = System.nanoTime() / 1000L;
            }
            lastFireUst = fireUst;
            mscCounter++;

            try {
                sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
                sendCompleteNotify(p.window, p.serial, Kind.PIXMAP, Mode.COPY, fireUst, mscCounter);
            } catch (Throwable ignored) {
                // Client may have disconnected before the present completed.
            }
        }
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 2; }

    @Override
    public int getNumErrors() { return 0; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        // Copy pixels immediately so the game's buffer is up-to-date on the XServer side.
        synchronized (content.renderLock) {
            content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
        }

        // PresentIdleNotify / PresentCompleteNotify are what actually pace the game's
        // render loop — the game blocks waiting for IdleNotify before it can reuse a
        // pixmap buffer, so delaying them creates real back-pressure.
        long interval = this.targetIntervalUs;
        long nowUst = System.nanoTime() / 1000L;

        if (interval <= 0L) {
            // No limit — fire immediately on the request handler thread.
            long msc = nowUst / FAKE_INTERVAL_DEFAULT_US;
            sendIdleNotify(window, pixmap, serial, idleFence);
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, nowUst, msc);
        } else {
            // Hand off to the pacing thread. Always enqueue (never fire inline here)
            // so present ordering is preserved across rapid bursts.
            pacingQueue.offer(new PendingPresent(window, pixmap, serial, idleFence,
                    nowUst, limitGeneration.get()));
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            final Texture oldTexture = content.getTexture();
            XServerView xServerView = client.xServer.getRenderer().xServerView;
            Objects.requireNonNull(oldTexture);
            xServerView.queueEvent(() -> VortekRendererComponent.destroyTexture(oldTexture));
            content.setTexture(new GPUImage(content.width, content.height));
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
