package com.castbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;

public class CastService extends Service {
    static {
        System.loadLibrary("native_sender");
    }

    private static volatile CastService instance;
    private static volatile int remoteVolume = 40;

    public static int getRemoteVolume() {
        return remoteVolume;
    }

    public static void setRemoteVolume(int v) {
        remoteVolume = Math.max(0, Math.min(100, v));
        CastService svc = instance;
        if (svc != null) svc.nativeSetVolume(remoteVolume);
    }

    public static void adjustRemoteVolume(int delta) {
        setRemoteVolume(remoteVolume + delta);
    }

    // --- phone-speaker mute (test) ---
    private static volatile boolean phoneMuted = false;
    private static volatile int savedVolume = -1;

    public static boolean isPhoneMuted() {
        return phoneMuted;
    }

    public static boolean togglePhoneMute() {
        CastService svc = instance;
        if (svc == null) return false;
        AudioManager am = (AudioManager) svc.getSystemService(AUDIO_SERVICE);
        if (!phoneMuted) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            phoneMuted = true;
        } else {
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume < 0 ? 1 : savedVolume, 0);
            phoneMuted = false;
        }
        return phoneMuted;
    }

    private AudioRecord record;
    private Thread thread;
    private MediaSession session;
    private volatile boolean stop = false;
    private int rate = 44100;

    // Live capture diagnostics: actual negotiated rate, peak sample magnitude
    // since the last poll, cumulative read errors, frames read.
    static volatile int diagRate;
    static volatile int diagPeak;
    static volatile int diagErrors;
    static volatile long diagFrames;

    static boolean isRunning() { return instance != null; }

    static int takePeak() {
        int p = diagPeak;
        diagPeak = 0;
        return p;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        String host = intent.getStringExtra("host");
        if (host == null || host.isEmpty() || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Remote-volume media session: while this session is the active one,
        // the phone's volume keys drive the HOMEPOD's volume (cast-app
        // behaviour) instead of the phone's own stream.
        session = new MediaSession(this, "homepod-cast");
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
        session.setPlaybackToRemote(new VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, remoteVolume) {
            @Override
            public void onAdjustVolume(int direction) {
                CastService.adjustRemoteVolume(direction * 5);
                setCurrentVolume(remoteVolume);
            }

            @Override
            public void onSetVolumeTo(int volume) {
                CastService.setRemoteVolume(volume);
                setCurrentVolume(remoteVolume);
            }
        });

        // Foreground notification (mediaProjection type is declared in the manifest).
        NotificationChannel ch = new NotificationChannel(
                "cast", "Casting", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "cast")
                .setContentTitle("Casting to " + host)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF5B9DFF)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build();
        startForeground(1, n);

        MediaProjection mp = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(resultCode, data);

        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build();

        record = buildRecord(cfg, rate);
        if (record == null) { // some devices only allow 48 kHz capture
            rate = 48000;
            record = buildRecord(cfg, rate);
        }
        if (record == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // The device may silently override the requested rate, so tell the
        // native sender the rate the record ACTUALLY runs at.
        int actual = record.getSampleRate();
        if (actual > 0) rate = actual;

        int rc = nativeStart(host, rate);
        if (rc != 0) {
            record.release();
            record = null;
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        nativeSetVolume(remoteVolume);

        diagRate = rate;
        diagPeak = 0;
        diagErrors = 0;
        diagFrames = 0;

        record.startRecording();
        thread = new Thread(() -> {
            short[] buf = new short[2048];
            while (!stop) {
                int got = record.read(buf, 0, buf.length);
                if (got > 0) {
                    int pk = 0;
                    for (int i = 0; i < got; i++) {
                        int a = buf[i] < 0 ? -buf[i] : buf[i];
                        if (a > pk) pk = a;
                    }
                    if (pk > diagPeak) diagPeak = pk;
                    diagFrames += got;
                    nativeWrite(buf, got);
                } else if (got < 0) {
                    diagErrors++;
                    if (got == AudioRecord.ERROR_DEAD_OBJECT
                            || got == AudioRecord.ERROR_INVALID_OPERATION
                            || got == AudioRecord.ERROR) break;
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        }, "capture");
        thread.start();
        if (!phoneMuted) togglePhoneMute();   // mute the phone by default
        return START_NOT_STICKY;
    }

    private AudioRecord buildRecord(AudioPlaybackCaptureConfiguration cfg, int rate) {
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(rate * 4)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            return r.getState() == AudioRecord.STATE_INITIALIZED ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            record = null;
        }
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        nativeStop();
        if (session != null) {
            session.release();
            session = null;
        }
        if (phoneMuted) { // restore the phone volume on the way out
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume < 0 ? 1 : savedVolume, 0);
            phoneMuted = false;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private native int nativeStart(String host, int rate);
    private native void nativeWrite(short[] pcm, int samples);
    private native void nativeSetVolume(int pct);
    private native void nativeStop();
}
