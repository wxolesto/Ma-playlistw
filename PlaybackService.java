package com.arsenous.base;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;

public class PlaybackService extends Service {
    static final String CH = "arsenous_playback";
    static final int NID = 4711;

    public static PlaybackService inst;
    public static ArrayList<Track> pendingQ = null;
    public static int pendingIndex = 0;
    public static boolean pendingPlay = true;
    public static boolean pendingShuffle = false;
    public static int pendingRepeat = 0;
    public static int pendingStartPos = 0;

    // snapshot for the plugin (updated on the main thread)
    public static volatile boolean snapHas = false;
    public static volatile boolean snapPlaying = false;
    public static volatile long snapId = 0;
    public static volatile int snapPos = 0;
    public static volatile int snapDur = 0;
    public static volatile int snapIndex = 0;
    public static volatile int snapLen = 0;
    public static volatile boolean snapShuffle = false;
    public static volatile int snapRepeat = 0;

    public static class Track {
        public long id;
        public String title = "";
        public String artist = "";
        public String album = "";
        public long dur = 0;

        public Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    final ArrayList<Track> orig = new ArrayList<Track>();
    final ArrayList<Track> q = new ArrayList<Track>();
    int idx = -1;
    boolean shuffle = false;
    int repeat = 0;

    MediaPlayer mp;
    boolean prepared = false;
    boolean wantPlay = false;
    int seekAfter = 0;
    MediaSession session;
    AudioManager am;
    NotificationManager nm;
    boolean hasFocus = false;
    boolean resumeOnFocus = false;
    Bitmap art;
    final Handler h = new Handler(Looper.getMainLooper());
    BroadcastReceiver noisy;
    boolean foregroundStarted = false;

    final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateSnap();
            h.postDelayed(this, 400);
        }
    };

    final AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                hasFocus = false;
                resumeOnFocus = false;
                doPause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (isPlayingNow()) {
                    resumeOnFocus = true;
                    doPause();
                }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (mp != null) {
                    try { mp.setVolume(0.25f, 0.25f); } catch (Exception e) { }
                }
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                if (mp != null) {
                    try { mp.setVolume(1f, 1f); } catch (Exception e) { }
                }
                if (resumeOnFocus) {
                    resumeOnFocus = false;
                    doPlay();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "ArsenousBase");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doPlay(); }
            @Override public void onPause() { doPause(); }
            @Override public void onSkipToNext() { advance(false); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override public void onStop() { doPause(); }
        });
        session.setActive(true);
        noisy = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                doPause();
            }
        };
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        h.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        ensureForeground();
        if ("SET".equals(a)) {
            applyPending();
        } else if ("toggle".equals(a)) {
            if (isPlayingNow()) doPause(); else doPlay();
        } else if ("next".equals(a)) {
            advance(false);
        } else if ("prev".equals(a)) {
            previous();
        } else if ("close".equals(a)) {
            doPause();
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void ensureForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NID, n);
        }
        foregroundStarted = true;
    }

    // ---------- queue ----------
    void applyPending() {
        if (pendingQ == null || pendingQ.isEmpty()) return;
        orig.clear();
        orig.addAll(pendingQ);
        pendingQ = null;
        shuffle = pendingShuffle;
        repeat = pendingRepeat;
        int i = Math.max(0, Math.min(pendingIndex, orig.size() - 1));
        Track cur = orig.get(i);
        q.clear();
        if (shuffle) {
            ArrayList<Track> rest = new ArrayList<Track>(orig);
            rest.remove(cur);
            Collections.shuffle(rest);
            q.add(cur);
            q.addAll(rest);
            idx = 0;
        } else {
            q.addAll(orig);
            idx = i;
        }
        loadCurrent(pendingPlay, pendingStartPos);
    }

    Track cur() {
        if (idx >= 0 && idx < q.size()) return q.get(idx);
        return null;
    }

    void setShuffle(boolean on) {
        shuffle = on;
        Track c = cur();
        if (c == null) return;
        q.clear();
        if (on) {
            ArrayList<Track> rest = new ArrayList<Track>(orig);
            rest.remove(c);
            Collections.shuffle(rest);
            q.add(c);
            q.addAll(rest);
            idx = 0;
        } else {
            q.addAll(orig);
            idx = Math.max(0, orig.indexOf(c));
        }
        updateAll();
    }

    void insertNext(Track t) {
        if (q.isEmpty()) return;
        q.add(Math.min(q.size(), idx + 1), t);
        Track c = cur();
        int oi = c == null ? -1 : orig.indexOf(c);
        orig.add(Math.min(orig.size(), oi + 1), t);
        updateAll();
    }

    void append(Track t) {
        q.add(t);
        orig.add(t);
        updateAll();
    }

    void jump(int i) {
        if (i < 0 || i >= q.size()) return;
        idx = i;
        loadCurrent(true, 0);
    }

    // ---------- player ----------
    void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); } catch (Exception e) { }
            try { mp.release(); } catch (Exception e) { }
            mp = null;
        }
        prepared = false;
    }

    void loadCurrent(boolean play, int startPos) {
        releasePlayer();
        Track t = cur();
        if (t == null) {
            updateAll();
            return;
        }
        wantPlay = play;
        seekAfter = startPos;
        art = null;
        mp = new MediaPlayer();
        try {
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (Exception e) { }
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(getApplicationContext(), t.uri());
        } catch (Exception e) {
            skipBroken();
            return;
        }
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer m) {
                prepared = true;
                if (seekAfter > 0) {
                    try { m.seekTo(seekAfter); } catch (Exception e) { }
                }
                if (wantPlay) doPlay();
                updateAll();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer m) {
                onComplete();
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer m, int what, int extra) {
                skipBroken();
                return true;
            }
        });
        try {
            mp.prepareAsync();
        } catch (Exception e) {
            skipBroken();
            return;
        }
        loadArtAsync(t);
        updateAll();
    }

    void skipBroken() {
        // move on to the next song, but stop if we have tried the whole list
        if (q.size() <= 1) {
            wantPlay = false;
            updateAll();
            return;
        }
        if (idx + 1 < q.size()) {
            idx++;
            loadCurrent(true, 0);
        } else {
            wantPlay = false;
            updateAll();
        }
    }

    boolean requestFocus() {
        if (hasFocus) return true;
        int r = am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasFocus;
    }

    boolean isPlayingNow() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    void doPlay() {
        wantPlay = true;
        if (mp != null && prepared) {
            if (!requestFocus()) return;
            try { mp.start(); } catch (Exception e) { }
        }
        updateAll();
    }

    void doPause() {
        wantPlay = false;
        if (mp != null && prepared) {
            try {
                if (mp.isPlaying()) mp.pause();
            } catch (Exception e) { }
        }
        updateAll();
    }

    void seekTo(int ms) {
        if (mp != null && prepared) {
            try { mp.seekTo(ms); } catch (Exception e) { }
        } else {
            seekAfter = ms;
        }
        updateAll();
    }

    void onComplete() {
        if (repeat == 2 && mp != null) {
            try {
                mp.seekTo(0);
                mp.start();
            } catch (Exception e) { }
            updateAll();
            return;
        }
        advance(true);
    }

    void advance(boolean auto) {
        if (q.isEmpty()) return;
        int n = idx + 1;
        if (n >= q.size()) {
            if (repeat == 1) {
                if (shuffle) {
                    Collections.shuffle(q);
                }
                n = 0;
            } else if (auto) {
                idx = 0;
                loadCurrent(false, 0);
                return;
            } else {
                return;
            }
        }
        idx = n;
        loadCurrent(true, 0);
    }

    void previous() {
        if (q.isEmpty()) return;
        if (getPos() > 3000) {
            seekTo(0);
            return;
        }
        int n = idx - 1;
        if (n < 0) n = (repeat == 1) ? q.size() - 1 : 0;
        idx = n;
        loadCurrent(true, 0);
    }

    int getPos() {
        try {
            if (mp != null && prepared) return mp.getCurrentPosition();
        } catch (Exception e) { }
        return seekAfter;
    }

    int getDur() {
        try {
            if (mp != null && prepared) return mp.getDuration();
        } catch (Exception e) { }
        Track t = cur();
        return t == null ? 0 : (int) t.dur;
    }

    // ---------- plugin commands (main thread) ----------
    public void cmd(String c, int v) {
        if ("toggle".equals(c)) {
            if (isPlayingNow() || wantPlay) doPause(); else doPlay();
        } else if ("play".equals(c)) {
            doPlay();
        } else if ("pause".equals(c)) {
            doPause();
        } else if ("next".equals(c)) {
            advance(false);
        } else if ("prev".equals(c)) {
            previous();
        } else if ("seek".equals(c)) {
            seekTo(v);
        } else if ("shuffle".equals(c)) {
            setShuffle(v == 1);
        } else if ("repeat".equals(c)) {
            repeat = v;
            updateAll();
        } else if ("jump".equals(c)) {
            jump(v);
        } else if ("stop".equals(c)) {
            doPause();
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
        }
    }

    // ---------- session + notification ----------
    void updateSnap() {
        Track t = cur();
        snapHas = t != null;
        snapPlaying = isPlayingNow();
        snapId = t == null ? 0 : t.id;
        snapPos = getPos();
        snapDur = getDur();
        snapIndex = idx;
        snapLen = q.size();
        snapShuffle = shuffle;
        snapRepeat = repeat;
    }

    void updateAll() {
        updateSnap();
        try {
            Track t = cur();
            MediaMetadata.Builder mb = new MediaMetadata.Builder();
            if (t != null) {
                mb.putString(MediaMetadata.METADATA_KEY_TITLE, t.title);
                mb.putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist);
                mb.putString(MediaMetadata.METADATA_KEY_ALBUM, t.album);
                mb.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) getDur());
                if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(mb.build());
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            boolean pl = isPlayingNow();
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(pl ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, (long) getPos(), pl ? 1f : 0f)
                    .build();
            session.setPlaybackState(ps);
        } catch (Exception e) { }
        if (foregroundStarted) {
            try {
                nm.notify(NID, buildNotification());
            } catch (Exception e) { }
        }
    }

    PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        Track t = cur();
        boolean pl = isPlayingNow() || wantPlay;
        b.setSmallIcon(getApplicationInfo().icon);
        b.setContentTitle(t == null ? "Arsenous base" : t.title);
        b.setContentText(t == null ? "" : t.artist);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setOngoing(pl);
        if (art != null) b.setLargeIcon(art);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", act("prev", 11)).build());
        if (pl) {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pause", act("toggle", 12)).build());
        } else {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, "Play", act("toggle", 12)).build());
        }
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", act("next", 13)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Close", act("close", 14)).build());
        Notification.MediaStyle st = new Notification.MediaStyle();
        st.setMediaSession(session.getSessionToken());
        st.setShowActionsInCompactView(0, 1, 2);
        b.setStyle(st);
        return b.build();
    }

    void loadArtAsync(final Track t) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = loadArt(getApplicationContext(), t.id, 512);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cur() == t) {
                            art = bm;
                            updateAll();
                        }
                    }
                });
            }
        }).start();
    }

    public static Bitmap loadArt(Context c, long id, int size) {
        Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        Bitmap bm = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, u);
            byte[] b = r.getEmbeddedPicture();
            if (b != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                bm = BitmapFactory.decodeByteArray(b, 0, b.length, o2);
            }
        } catch (Exception e) {
            bm = null;
        } finally {
            try { r.release(); } catch (Exception e) { }
        }
        if (bm == null && Build.VERSION.SDK_INT >= 29) {
            try {
                bm = c.getContentResolver().loadThumbnail(u, new Size(size, size), null);
            } catch (Exception e) {
                bm = null;
            }
        }
        return bm;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(ticker);
        releasePlayer();
        try { unregisterReceiver(noisy); } catch (Exception e) { }
        try { session.setActive(false); session.release(); } catch (Exception e) { }
        try { am.abandonAudioFocus(focusL); } catch (Exception e) { }
        snapHas = false;
        snapPlaying = false;
        inst = null;
        super.onDestroy();
    }
}
