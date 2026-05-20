package org.fmod;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/**
 * Compatibility shim for FMOD Android native libraries.
 *
 * The FMOD native runtime bundled with the launcher manager expects
 * org.fmod.FMOD.getAudioDevices(int), while the fmod.jar shipped by the Godot
 * FMOD plugin exposes newer getDevices/getDeviceName/getDeviceType helpers
 * instead. Shipping this source class makes the Java side match the native
 * libfmod.so ABI used by the Android runtime payload.
 */
public class FMOD {
    private static final String TAG = "FMOD";
    private static Context gContext;

    public FMOD() {
    }

    public static void init(Context context) {
        gContext = context != null ? context.getApplicationContext() : null;
    }

    public static void close() {
        gContext = null;
    }

    public static boolean checkInit() {
        return gContext != null;
    }

    public static AssetManager getAssetManager() {
        return gContext != null ? gContext.getAssets() : null;
    }

    public static boolean supportsSpatial() {
        if (gContext == null || Build.VERSION.SDK_INT < 32) {
            return false;
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return false;
            }
            android.media.Spatializer spatializer = audioManager.getSpatializer();
            return spatializer != null
                && spatializer.getImmersiveAudioLevel() != 0
                && spatializer.isAvailable()
                && spatializer.isEnabled();
        } catch (Throwable throwable) {
            Log.w(TAG, "supportsSpatial failed", throwable);
            return false;
        }
    }

    public static boolean supportsLowLatency() {
        int blockSize = getOutputBlockSize();
        return lowLatencyFlag() && proAudioFlag() && !isBluetoothOn() && blockSize > 0 && blockSize <= 1024;
    }

    public static boolean lowLatencyFlag() {
        return gContext != null
            && gContext.getPackageManager() != null
            && gContext.getPackageManager().hasSystemFeature("android.hardware.audio.low_latency");
    }

    public static boolean proAudioFlag() {
        return gContext != null
            && gContext.getPackageManager() != null
            && gContext.getPackageManager().hasSystemFeature("android.hardware.audio.pro");
    }

    public static boolean supportsAAudio() {
        return Build.VERSION.SDK_INT >= 27;
    }

    public static int getOutputSampleRate() {
        if (gContext == null) {
            return 0;
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return 0;
            }
            String sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            return sampleRate != null ? Integer.parseInt(sampleRate) : 0;
        } catch (Throwable throwable) {
            Log.w(TAG, "getOutputSampleRate failed", throwable);
            return 0;
        }
    }

    public static int getOutputBlockSize() {
        if (gContext == null) {
            return 0;
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return 0;
            }
            String framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            return framesPerBuffer != null ? Integer.parseInt(framesPerBuffer) : 0;
        } catch (Throwable throwable) {
            Log.w(TAG, "getOutputBlockSize failed", throwable);
            return 0;
        }
    }

    public static boolean isBluetoothOn() {
        if (gContext == null) {
            return false;
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            return audioManager != null && (audioManager.isBluetoothA2dpOn() || audioManager.isBluetoothScoOn());
        } catch (Throwable throwable) {
            Log.w(TAG, "isBluetoothOn failed", throwable);
            return false;
        }
    }

    public static String getDeviceName(int deviceId) {
        AudioDeviceInfo device = findAudioDevice(deviceId);
        CharSequence productName = device != null ? device.getProductName() : null;
        return productName != null ? productName.toString() : "";
    }

    public static int getDeviceType(int deviceId) {
        AudioDeviceInfo device = findAudioDevice(deviceId);
        return device != null ? device.getType() : 0;
    }

    public static int[] getDevices(int flags) {
        AudioDeviceInfo[] devices = getAudioDevices(flags);
        int[] ids = new int[devices.length];
        for (int i = 0; i < devices.length; i++) {
            ids[i] = devices[i].getId();
        }
        return ids;
    }

    public static AudioDeviceInfo[] getAudioDevices(int flags) {
        if (gContext == null || Build.VERSION.SDK_INT < 23) {
            return new AudioDeviceInfo[0];
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return new AudioDeviceInfo[0];
            }
            AudioDeviceInfo[] devices = audioManager.getDevices(flags);
            return devices != null ? devices : new AudioDeviceInfo[0];
        } catch (Throwable throwable) {
            Log.w(TAG, "getAudioDevices failed", throwable);
            return new AudioDeviceInfo[0];
        }
    }

    public static int fileDescriptorFromUri(String uri) {
        return -1;
    }

    private static AudioDeviceInfo findAudioDevice(int deviceId) {
        for (AudioDeviceInfo device : getAudioDevices(AudioManager.GET_DEVICES_ALL)) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    private static void OutputAAudioHeadphonesChanged() {
    }

    private static void SetInputEnumerationChanged() {
    }

    private static void SetOutputEnumerationChanged() {
    }
}
