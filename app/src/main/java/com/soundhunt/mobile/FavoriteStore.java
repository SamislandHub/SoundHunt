package com.soundhunt.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FavoriteStore {
    private static final String PREFS = "soundhunt";
    private static final String KEY = "favorites";
    private final SharedPreferences prefs;

    public FavoriteStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Track> all() {
        List<Track> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Track track = Track.fromJson(item);
                // 过滤已下线的 Apple 试听源历史收藏，避免收藏页残留不可播条目
                if (!Track.isRetiredSource(track.source)) out.add(track);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized boolean contains(Track track) {
        if (track == null) return false;
        for (Track t : all()) if (t.stableId().equals(track.stableId())) return true;
        return false;
    }

    public synchronized boolean toggle(Track track) {
        List<Track> tracks = all();
        boolean removed = false;
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (tracks.get(i).stableId().equals(track.stableId())) {
                tracks.remove(i);
                removed = true;
            }
        }
        if (!removed) tracks.add(0, track);
        save(tracks);
        return !removed;
    }

    private void save(List<Track> tracks) {
        JSONArray array = new JSONArray();
        try {
            for (Track t : tracks) array.put(t.toJson());
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }
}
