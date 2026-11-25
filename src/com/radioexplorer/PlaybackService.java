package com.radioexplorer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

public final class PlaybackService extends Service {
    static final String PLAY = "com.radioexplorer.PLAY";
    static final String PAUSE = "com.radioexplorer.PAUSE";
    static final String STOP = "com.radioexplorer.STOP";
    static final String EXTRA_NAME = "name", EXTRA_URL = "url";
    private static final String CHANNEL = "playback";
    private final IBinder binder = new LocalBinder();
    private LibVLC vlc;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private PowerManager.WakeLock wakeLock;
    private String name = "", url = "", message = "";
    private int state = PlaybackState.STATE_STOPPED;
    private boolean focused, resumeOnFocus, foreground, desiredPlaying;
    private Runnable observer;

    final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focused = true;
            if (resumeOnFocus) { resumeOnFocus = false; resume(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeOnFocus = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
            pause(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focused = false;
            resumeOnFocus = false;
            pause(true);
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(true); }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioExplorer:playback");
        vlc = new LibVLC(this);
        player = new MediaPlayer(vlc);
        player.setEventListener(event -> {
            if (url.isEmpty() || !desiredPlaying) return;
            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    if (state != PlaybackState.STATE_PAUSED) update(PlaybackState.STATE_BUFFERING, "Conectando…");
                    break;
                case MediaPlayer.Event.Buffering:
                    if (state != PlaybackState.STATE_PAUSED) {
                        if (event.getBuffering() >= 100 && player.isPlaying())
                            update(PlaybackState.STATE_PLAYING, "En directo");
                        else if (event.getBuffering() < 100)
                            update(PlaybackState.STATE_BUFFERING, "Conectando…");
                    }
                    break;
                case MediaPlayer.Event.Playing:
                    update(PlaybackState.STATE_PLAYING, "En directo"); break;
                case MediaPlayer.Event.Paused:
                    update(PlaybackState.STATE_PAUSED, "En pausa"); break;
                case MediaPlayer.Event.EncounteredError:
                case MediaPlayer.Event.EndReached:
                    desiredPlaying = false;
                    update(PlaybackState.STATE_ERROR, "No se pudo reproducir la emisora.");
                    releaseFocus();
                    leaveForeground();
                    break;
                default: break;
            }
        });
        session = new MediaSession(this, "Radio Explorer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(true); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setActive(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW));
        }
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (PLAY.equals(action)) {
            String nextUrl = intent.getStringExtra(EXTRA_URL);
            String nextName = intent.getStringExtra(EXTRA_NAME);
            if (nextUrl != null && Catalog.playable(nextUrl) && nextName != null) {
                name = nextName;
                url = nextUrl;
                update(PlaybackState.STATE_BUFFERING, "Conectando…");
                startForeground(1, notification());
                foreground = true;
                startStream();
            } else if (!url.isEmpty()) resume();
            else stopSelf();
        } else if (PAUSE.equals(action)) pause(true);
        else if (STOP.equals(action)) stopPlayback();
        return START_NOT_STICKY;
    }

    void observe(Runnable listener) { observer = listener; if (observer != null) observer.run(); }
    String title() { return name; }
    String status() { return message; }
    boolean playing() { return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING; }
    boolean hasStation() { return !url.isEmpty(); }

    void resume() {
        if (url.isEmpty()) return;
        if (!focused && audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            desiredPlaying = false;
            update(PlaybackState.STATE_ERROR, "No se pudo obtener el control del audio.");
            leaveForeground();
            return;
        }
        focused = true;
        desiredPlaying = true;
        resumeOnFocus = false;
        session.setActive(true);
        startForeground(1, notification());
        foreground = true;
        if (!wakeLock.isHeld()) wakeLock.acquire();
        update(PlaybackState.STATE_BUFFERING, "Conectando…");
        player.play();
    }

    private void startStream() {
        player.stop();
        Media media = new Media(vlc, Uri.parse(url));
        media.setHWDecoderEnabled(false, false);
        player.setMedia(media);
        media.release();
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, name).build());
        resume();
    }

    void pause(boolean abandon) {
        resumeOnFocus = !abandon && resumeOnFocus;
        desiredPlaying = false;
        player.stop();
        update(PlaybackState.STATE_PAUSED, "En pausa");
        if (abandon) releaseFocus();
        leaveForeground();
    }

    void stopPlayback() {
        resumeOnFocus = false;
        desiredPlaying = false;
        player.stop();
        releaseFocus();
        url = "";
        name = "";
        update(PlaybackState.STATE_STOPPED, "");
        session.setActive(false);
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        foreground = false;
        stopSelf();
    }

    private void releaseFocus() {
        if (focused) audio.abandonAudioFocus(focusListener);
        focused = false;
    }

    private void leaveForeground() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (foreground) { stopForeground(false); foreground = false; }
    }

    private void update(int next, String text) {
        if (state == next && message.equals(text)) return;
        state = next;
        message = text;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(next, PlaybackState.PLAYBACK_POSITION_UNKNOWN, next == PlaybackState.STATE_PLAYING ? 1 : 0)
                .setErrorMessage(next == PlaybackState.STATE_ERROR ? text : null).build());
        if (foreground) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification());
        if (observer != null) observer.run();
    }

    private PendingIntent action(String action, int request) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (PLAY.equals(action) && Build.VERSION.SDK_INT >= 26)
            return PendingIntent.getForegroundService(this, request, intent, flags);
        return PendingIntent.getService(this, request, intent, flags);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean active = playing();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(name)
                .setContentText(message).setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active ? "Pausar" : "Reproducir", action(active ? PAUSE : PLAY, 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", action(STOP, 2))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1))
                .build();
    }

    @Override public void onDestroy() {
        observer = null;
        unregisterReceiver(noisy);
        releaseFocus();
        if (wakeLock.isHeld()) wakeLock.release();
        player.setEventListener(null);
        player.release();
        vlc.release();
        session.release();
        super.onDestroy();
    }
}
