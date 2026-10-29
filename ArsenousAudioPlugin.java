package com.arsenous.base;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
        name = "ArsenousAudio",
        permissions = {
                @Permission(alias = "audio", strings = {Manifest.permission.READ_MEDIA_AUDIO}),
                @Permission(alias = "storage", strings = {Manifest.permission.READ_EXTERNAL_STORAGE}),
                @Permission(alias = "notif", strings = {Manifest.permission.POST_NOTIFICATIONS})
        }
)
public class ArsenousAudioPlugin extends Plugin {

    private String accessAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        String alias = accessAlias();
        if (getPermissionState(alias) == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias(alias, call, "accessResult");
    }

    @PermissionCallback
    private void accessResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState(accessAlias()) == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotif(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED) {
            call.resolve();
            return;
        }
        requestPermissionForAlias("notif", call, "notifResult");
    }

    @PermissionCallback
    private void notifResult(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void scan(PluginCall call) {
        JSArray arr = new JSArray();
        Cursor c = null;
        try {
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0 AND " + MediaStore.Audio.Media.DURATION + ">=20000";
            c = getContext().getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    JSObject o = new JSObject();
                    o.put("id", c.getLong(0));
                    String title = c.getString(1);
                    String artist = c.getString(2);
                    String album = c.getString(3);
                    if (artist == null || artist.equals("<unknown>")) artist = "Unknown artist";
                    if (album == null || album.equals("<unknown>")) album = "Unknown album";
                    o.put("title", title == null ? "Untitled" : title);
                    o.put("artist", artist);
                    o.put("album", album);
                    o.put("dur", c.getLong(4));
                    o.put("added", c.getLong(5));
                    arr.put(o);
                }
            }
        } catch (Exception e) {
            call.reject("scan failed: " + e.getMessage());
            return;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception e) { }
            }
        }
        JSObject ret = new JSObject();
        ret.put("songs", arr);
        call.resolve(ret);
    }

    @PluginMethod
    public void getArt(PluginCall call) {
        long id = call.getData().optLong("id", 0);
        int size = call.getData().optInt("size", 300);
        JSObject ret = new JSObject();
        String out = "";
        try {
            Bitmap bm = PlaybackService.loadArt(getContext(), id, size);
            if (bm != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 82, bos);
                out = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception e) {
            out = "";
        }
        ret.put("data", out);
        call.resolve(ret);
    }

    private PlaybackService.Track parseTrack(JSONObject o) {
        PlaybackService.Track t = new PlaybackService.Track();
        t.id = o.optLong("id", 0);
        t.title = o.optString("title", "");
        t.artist = o.optString("artist", "");
        t.album = o.optString("album", "");
        t.dur = o.optLong("dur", 0);
        return t;
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        try {
            JSONObject d = call.getData();
            org.json.JSONArray arr = d.getJSONArray("tracks");
            ArrayList<PlaybackService.Track> list = new ArrayList<PlaybackService.Track>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(parseTrack(arr.getJSONObject(i)));
            }
            if (list.isEmpty()) {
                call.reject("empty queue");
                return;
            }
            PlaybackService.pendingQ = list;
            PlaybackService.pendingIndex = d.optInt("index", 0);
            PlaybackService.pendingPlay = d.optBoolean("play", true);
            PlaybackService.pendingShuffle = d.optBoolean("shuffle", false);
            PlaybackService.pendingRepeat = d.optInt("repeat", 0);
            PlaybackService.pendingStartPos = d.optInt("startPos", 0);
            Context ctx = getContext();
            Intent i = new Intent(ctx, PlaybackService.class);
            i.setAction("SET");
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("setQueue failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void command(PluginCall call) {
        final String cmd = call.getData().optString("cmd", "");
        final int v = call.getData().optInt("value", 0);
        final PlaybackService s = PlaybackService.inst;
        if (s != null) {
            s.h.post(new Runnable() {
                @Override
                public void run() {
                    s.cmd(cmd, v);
                }
            });
        }
        call.resolve();
    }

    @PluginMethod
    public void insertNext(PluginCall call) {
        final PlaybackService s = PlaybackService.inst;
        try {
            final PlaybackService.Track t = parseTrack(call.getData().getJSONObject("track"));
            final boolean next = call.getData().optBoolean("next", true);
            if (s != null) {
                s.h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (next) s.insertNext(t); else s.append(t);
                    }
                });
            }
        } catch (Exception e) { }
        call.resolve();
    }

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject r = new JSObject();
        try {
            r.put("has", PlaybackService.snapHas);
            r.put("playing", PlaybackService.snapPlaying);
            r.put("id", PlaybackService.snapId);
            r.put("pos", PlaybackService.snapPos);
            r.put("dur", PlaybackService.snapDur);
            r.put("index", PlaybackService.snapIndex);
            r.put("len", PlaybackService.snapLen);
            r.put("shuffle", PlaybackService.snapShuffle);
            r.put("repeat", PlaybackService.snapRepeat);
        } catch (Exception e) { }
        call.resolve(r);
    }

    @PluginMethod
    public void getQueue(PluginCall call) {
        final PlaybackService s = PlaybackService.inst;
        JSObject r = new JSObject();
        JSArray ids = new JSArray();
        int index = 0;
        if (s != null) {
            final long[][] out = new long[1][];
            final int[] ix = new int[1];
            final CountDownLatch l = new CountDownLatch(1);
            s.h.post(new Runnable() {
                @Override
                public void run() {
                    long[] a = new long[s.q.size()];
                    for (int i = 0; i < a.length; i++) a[i] = s.q.get(i).id;
                    out[0] = a;
                    ix[0] = s.idx;
                    l.countDown();
                }
            });
            try { l.await(1, TimeUnit.SECONDS); } catch (Exception e) { }
            if (out[0] != null) {
                for (long id : out[0]) ids.put(id);
            }
            index = ix[0];
        }
        r.put("ids", ids);
        r.put("index", index);
        call.resolve(r);
    }

    @PluginMethod
    public void volume(PluginCall call) {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        if (call.getData().has("set")) {
            double f = call.getData().optDouble("set", 0.5);
            int v = (int) Math.round(Math.max(0, Math.min(1, f)) * max);
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            } catch (Exception e) { }
        }
        JSObject r = new JSObject();
        r.put("level", (double) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        call.resolve(r);
    }

    @PluginMethod
    public void setBars(PluginCall call) {
        final String col = call.getData().optString("color", "#10141c");
        try {
            final int c = Color.parseColor(col);
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        getActivity().getWindow().setStatusBarColor(c);
                        getActivity().getWindow().setNavigationBarColor(c);
                    } catch (Exception e) { }
                }
            });
        } catch (Exception e) { }
        call.resolve();
    }
}
