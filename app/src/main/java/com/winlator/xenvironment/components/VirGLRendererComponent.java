package com.winlator.xenvironment.components;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.Keep;

import com.winlator.renderer.VulkanRenderer;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.io.IOException;

public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private static final String TAG = "VirGLRendererComponent";
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    // Detected once on the first flushFrontbuffer call.
    // GL_BGRA_EXT requires GL_EXT_read_format_bgra; fall back to GL_RGBA + swapRB.
    private volatile int readPixelsFormat = GLES11Ext.GL_BGRA;
    private volatile boolean readFormatDetected = false;
    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        Log.d(TAG, "Starting...");
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.start();
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping...");
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private void killConnection(int fd) {
        connector.killConnection(connector.getClient(fd));
    }

    @Keep
    private long getSharedEGLContext() {
        return 0;
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        long clientPtr = (long)client.getTag();
        destroyClient(clientPtr);
    }

    @Override
    public void handleNewConnection(Client client) {
        Log.d(TAG, "New connection fd=" + client.clientSocket.fd);
        long clientPtr = handleNewConnection(client.clientSocket.fd);
        client.setTag(clientPtr);
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        long clientPtr = (long)client.getTag();
        handleRequest(clientPtr);
        return true;
    }

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) return;

        synchronized (drawable.renderLock) {
            java.nio.ByteBuffer buf = drawable.getData();
            if (buf == null) return;
            buf.rewind();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glReadPixels(0, 0, drawable.width, drawable.height,
                    readPixelsFormat, GLES20.GL_UNSIGNED_BYTE, buf);

            if (!readFormatDetected) {
                readFormatDetected = true;
                int err = GLES20.glGetError();
                if (err != GLES20.GL_NO_ERROR) {
                    Log.w(TAG, "GL_BGRA_EXT not supported (err=0x" + Integer.toHexString(err)
                            + "), falling back to GL_RGBA + swapRB");
                    readPixelsFormat = GLES20.GL_RGBA;
                    // Re-read with the correct format for this first frame.
                    buf.rewind();
                    GLES20.glReadPixels(0, 0, drawable.width, drawable.height,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                    // Tell the Vulkan compositor to swap R/B channels at composite time.
                    VulkanRenderer renderer = xServer.getRenderer();
                    if (renderer != null) renderer.setSwapRB(true);
                } else {
                    Log.d(TAG, "GL_BGRA_EXT read pixels OK");
                }
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            buf.rewind();
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) onDrawListener.run();
    }

    private native long handleNewConnection(int fd);

    private native void handleRequest(long clientPtr);

    private native void destroyClient(long clientPtr);

    private native void destroyRenderer(long clientPtr);
}
