package com.example.pleostest.util;

import android.util.Log;

public class GNCLog {

    private static final String LOG_TAG = "GNC";

    public static boolean FORCE_LOG = true;

    public static void d(String message) {
        if (FORCE_LOG) {
            Log.i(LOG_TAG, message);
        } else {
            Log.d(LOG_TAG, message);
        }
    }

    public static void v(String message) {
        if (FORCE_LOG) {
            Log.i(LOG_TAG, message);
        } else {
            Log.v(LOG_TAG, message);
        }
    }

    public static void i(String message) {
        Log.i(LOG_TAG, message);
    }

    public static void w(String message) {
        Log.w(LOG_TAG, message);
    }

    public static void e(String message) {
        Log.e(LOG_TAG, message);
    }

    public static void e(String message, Throwable tr) {
        Log.e(LOG_TAG, message, tr);
    }

}
