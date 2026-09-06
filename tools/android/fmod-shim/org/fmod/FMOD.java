package org.fmod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;

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
    private static final int TYPE_REMOTE_SUBMIX = 25;
    private static volatile Context gContext;
    private static volatile boolean gAudioCompatibilityMode;
    private static final PluginBroadcastReceiver gPluginBroadcastReceiver = new PluginBroadcastReceiver();
    private static volatile PluginAudioDeviceCallback gPluginAudioDeviceCallback;

    public FMOD() {
    }

    /** Must be set before the native FMOD system is initialized. */
    public static void setAudioCompatibilityMode(boolean enabled) {
        gAudioCompatibilityMode = enabled;
        Log.i(TAG, "Audio compatibility mode=" + enabled + "; AAudio/low-latency=" + !enabled);
    }

    public static synchronized void init(Context context) {
        Context applicationContext = context != null ? context.getApplicationContext() : null;
        // GodotApp initializes before native startup; the Godot plugin calls us again.
        if (applicationContext != null && applicationContext == gContext) {
            return;
        }
        close();
        gContext = applicationContext;
        if (gContext == null) {
            return;
        }

        IntentFilter headsetFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gContext.registerReceiver(gPluginBroadcastReceiver, headsetFilter, Context.RECEIVER_EXPORTED);
            } else {
                gContext.registerReceiver(gPluginBroadcastReceiver, headsetFilter);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to register headset plug receiver", throwable);
        }

        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        try {
            gPluginAudioDeviceCallback = new PluginAudioDeviceCallback(audioManager.getDevices(AudioManager.GET_DEVICES_ALL));
            audioManager.registerAudioDeviceCallback(gPluginAudioDeviceCallback, null);
        } catch (Throwable throwable) {
            gPluginAudioDeviceCallback = null;
            Log.w(TAG, "Unable to register audio device callback", throwable);
        }
    }

    public static synchronized void close() {
        Context context = gContext;
        if (context != null) {
            try {
                context.unregisterReceiver(gPluginBroadcastReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may not have been registered if initialization failed.
            }
            if (Build.VERSION.SDK_INT >= 23 && gPluginAudioDeviceCallback != null) {
                try {
                    AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (audioManager != null) {
                        audioManager.unregisterAudioDeviceCallback(gPluginAudioDeviceCallback);
                    }
                } catch (Throwable throwable) {
                    Log.w(TAG, "Unable to unregister audio device callback", throwable);
                }
            }
        }
        gPluginAudioDeviceCallback = null;
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
        return !gAudioCompatibilityMode && lowLatencyFlag() && !isBluetoothOn() && blockSize > 0 && blockSize <= 1024;
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
        return !gAudioCompatibilityMode && Build.VERSION.SDK_INT >= 27;
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
        for (AudioDeviceInfo device : getAudioDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                    return true;
                default:
                    break;
            }
        }
        return false;
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

    /** Compatibility API used by the older FMOD runtime bundled with the launcher. */
    public static AudioDeviceInfo[] getAudioDevices(int flags) {
        if (gContext == null || Build.VERSION.SDK_INT < 23) {
            return new AudioDeviceInfo[0];
        }
        try {
            AudioManager audioManager = (AudioManager) gContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return new AudioDeviceInfo[0];
            }
            // The bundled native library uses this older API, not getDevices().
            return filterDevices(audioManager.getDevices(flags));
        } catch (Throwable throwable) {
            Log.w(TAG, "getAudioDevices failed", throwable);
            return new AudioDeviceInfo[0];
        }
    }

    public static int fileDescriptorFromUri(String uri) {
        if (gContext == null || uri == null) {
            return -1;
        }
        try {
            Uri parsedUri = Uri.parse(uri);
            ParcelFileDescriptor descriptor = gContext.getContentResolver().openFileDescriptor(parsedUri, "r");
            return descriptor != null ? descriptor.detachFd() : -1;
        } catch (FileNotFoundException exception) {
            return -1;
        } catch (Throwable throwable) {
            Log.w(TAG, "fileDescriptorFromUri failed", throwable);
            return -1;
        }
    }

    private static AudioDeviceInfo[] filterDevices(AudioDeviceInfo[] devices) {
        ArrayList<AudioDeviceInfo> filtered = new ArrayList<>();
        if (devices == null) {
            return new AudioDeviceInfo[0];
        }
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.getType() != TYPE_REMOTE_SUBMIX) {
                filtered.add(device);
            }
        }
        return filtered.toArray(new AudioDeviceInfo[0]);
    }

    private static AudioDeviceInfo findAudioDevice(int deviceId) {
        for (AudioDeviceInfo device : getAudioDevices(AudioManager.GET_DEVICES_ALL)) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    private static native void OutputAAudioHeadphonesChanged();

    private static native void SetInputEnumerationChanged();

    private static native void SetOutputEnumerationChanged();

    private static void notifyOutputRouteChanged() {
        // Enumeration and AAudio stream reconnection are distinct native signals.
        try {
            SetOutputEnumerationChanged();
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "FMOD output enumeration callback is unavailable", error);
        }
        try {
            OutputAAudioHeadphonesChanged();
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "FMOD headphone callback is unavailable", error);
        }
    }

    private static final class PluginBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (gContext != null && intent != null && Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                notifyOutputRouteChanged();
            }
        }
    }

    private static final class PluginAudioDeviceCallback extends AudioDeviceCallback {
        private final HashSet<Integer> deviceSet = new HashSet<>();

        PluginAudioDeviceCallback(AudioDeviceInfo[] devices) {
            for (AudioDeviceInfo device : filterDevices(devices)) {
                deviceSet.add(device.getId());
            }
        }

        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            updateDevices(devices, true);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            updateDevices(devices, false);
        }

        private void updateDevices(AudioDeviceInfo[] devices, boolean added) {
            if (this != gPluginAudioDeviceCallback) {
                return;
            }
            boolean inputChanged = false;
            boolean outputChanged = false;
            for (AudioDeviceInfo device : filterDevices(devices)) {
                int deviceId = device.getId();
                boolean known = deviceSet.contains(deviceId);
                if (added ? known : !known) {
                    continue;
                }
                inputChanged |= device.isSource();
                outputChanged |= device.isSink();
                if (added) {
                    deviceSet.add(deviceId);
                } else {
                    deviceSet.remove(deviceId);
                }
            }
            if (inputChanged) {
                try {
                    SetInputEnumerationChanged();
                } catch (UnsatisfiedLinkError error) {
                    Log.w(TAG, "FMOD input enumeration callback is unavailable", error);
                }
            }
            if (outputChanged) {
                // Also covers USB and Bluetooth outputs which do not send HEADSET_PLUG.
                notifyOutputRouteChanged();
            }
        }
    }
}
