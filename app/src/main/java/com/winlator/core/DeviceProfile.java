package com.winlator.core;

import android.os.Build;

import java.util.Locale;

public final class DeviceProfile {
    public static final String REDMAGIC_11_PRO_MODEL = "NX809J";

    private DeviceProfile() {}

    public static boolean isRedmagic11Pro() {
        return containsDeviceId(Build.MODEL)
                || containsDeviceId(Build.DEVICE)
                || containsDeviceId(Build.PRODUCT);
    }

    public static String getDeviceSummary() {
        return "manufacturer=" + safe(Build.MANUFACTURER)
                + ", brand=" + safe(Build.BRAND)
                + ", model=" + safe(Build.MODEL)
                + ", device=" + safe(Build.DEVICE)
                + ", product=" + safe(Build.PRODUCT)
                + ", board=" + safe(Build.BOARD)
                + ", soc=" + getSocModel();
    }

    public static String getSocModel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return safe(Build.SOC_MODEL);
        }
        return "";
    }

    private static boolean containsDeviceId(String value) {
        return value != null && value.toUpperCase(Locale.ENGLISH).contains(REDMAGIC_11_PRO_MODEL);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
