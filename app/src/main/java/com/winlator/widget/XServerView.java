package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.collection.MutableObjectList;

import com.winlator.core.Callback;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.xserver.XServer;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback {
    private final VulkanRenderer renderer;
    // private final ArrayList<Callback<MotionEvent>> mouseEventCallbacks = new ArrayList<>();
    private final XServer xServer;
    private int frameRateLimit = 0;

    /**
     * Single-threaded executor used for all event-queue tasks (scene updates, window
     * modifications, etc.) that must not block the UI thread. Replaces the thread
     * that GLSurfaceView used to manage automatically.
     */
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.xServer = xServer;
        renderer = new VulkanRenderer(this, xServer);
        getHolder().addCallback(this);
    }

    // -------------------------------------------------------------------------
    // SurfaceHolder.Callback
    // -------------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderer.onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        renderer.onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        renderer.onSurfaceDestroyed();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public XServer getxServer() {
        return xServer;
    }

    public VulkanRenderer getRenderer() {
        return renderer;
    }

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    /**
     * Stores the desired frame-rate cap. The Vulkan compositor honours the cap in
     * its render loop; no explicit requestRender() call is needed.
     */
    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
    }

    /**
     * Runs r on the dedicated event executor. All X11 window-modification callbacks
     * and scene-update tasks are queued here so the UI thread is never blocked.
     * Replaces GLSurfaceView.queueEvent().
     */
    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    /**
     * Legacy compatibility shim for GLRenderer which calls requestRender() on XServerView.
     * The Vulkan compositor is event-driven and does not require explicit render requests;
     * this method is a no-op.
     */
    public void requestRender() {
        // No-op: Vulkan render loop is driven by vkQueuePresentKHR / condition variables.
    }

    /**
     * Called when the hosting activity/fragment is paused.
     * Surface lifecycle is handled via SurfaceHolder.Callback.
     */
    public void onPause() {
        // No-op: surface lifecycle is handled via SurfaceHolder.Callback.
    }

    /**
     * Called when the hosting activity/fragment is resumed.
     * Surface lifecycle is handled via SurfaceHolder.Callback.
     */
    public void onResume() {
        // No-op: surface lifecycle is handled via SurfaceHolder.Callback.
    }

    // Commented-out pointer capture / focus / motion event plumbing retained for
    // reference. These require additional wiring (releasePointerCapture, etc.) and
    // are left disabled until the input refactor is complete.

    // public void addPointerEventListener(Callback<MotionEvent> listener) { ... }
    // public void removePointerEventListener(Callback<MotionEvent> listener) { ... }
    // public void clearPointerEventListeners() { ... }
    // @Override public boolean onCapturedPointerEvent(MotionEvent event) { ... }
    // @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { ... }
    // @Override protected boolean dispatchGenericPointerEvent(MotionEvent event) { ... }
    // @Override protected void onFocusChanged(...) { ... }
    // @Override public boolean dispatchCapturedPointerEvent(MotionEvent event) { ... }
    // @Override public void onPointerCaptureChange(boolean hasCapture) { ... }
    // @Override public void onWindowFocusChanged(boolean hasWindowFocus) { ... }
}
