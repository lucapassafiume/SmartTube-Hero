package com.liskovsoft.smartyoutubetv2.common.discover;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small local memory for the "Scopri" section.
 * Keeps the videos already played and the videos/topics recently proposed,
 * so every shuffle shows something new.
 */
public class DiscoverStore {
    private static final String PREFS = "discover_prefs";
    private static final String KEY_PLAYED = "played";
    private static final String KEY_SHOWN = "shown";
    private static final String KEY_TOPICS = "topics";
    private static final int MAX_PLAYED = 3000;
    private static final int MAX_SHOWN = 500;
    private static final int MAX_TOPICS = 40;
    private static DiscoverStore sInstance;
    private final SharedPreferences mPrefs;
    private final LinkedHashSet<String> mPlayed;
    private final LinkedHashSet<String> mShown;
    private final LinkedHashSet<String> mTopics;

    private DiscoverStore(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mPlayed = read(KEY_PLAYED);
        mShown = read(KEY_SHOWN);
        mTopics = read(KEY_TOPICS);
    }

    public static synchronized DiscoverStore instance(Context context) {
        if (sInstance == null) {
            sInstance = new DiscoverStore(context);
        }
        return sInstance;
    }

    public synchronized void markPlayed(String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        mPlayed.remove(videoId);
        mPlayed.add(videoId);
        trim(mPlayed, MAX_PLAYED);
        write(KEY_PLAYED, mPlayed);
    }

    public synchronized void markShown(Collection<String> videoIds) {
        for (String id : videoIds) {
            if (!TextUtils.isEmpty(id)) {
                mShown.remove(id);
                mShown.add(id);
            }
        }
        trim(mShown, MAX_SHOWN);
        write(KEY_SHOWN, mShown);
    }

    public synchronized void markTopic(String topic) {
        mTopics.remove(topic);
        mTopics.add(topic);
        trim(mTopics, MAX_TOPICS);
        write(KEY_TOPICS, mTopics);
    }

    public synchronized Set<String> getPlayed() {
        return new HashSet<>(mPlayed);
    }

    public synchronized Set<String> getShown() {
        return new HashSet<>(mShown);
    }

    public synchronized Set<String> getRecentTopics() {
        return new HashSet<>(mTopics);
    }

    private LinkedHashSet<String> read(String key) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String data = mPrefs.getString(key, "");
        if (!TextUtils.isEmpty(data)) {
            for (String item : data.split("\n")) {
                if (!item.isEmpty()) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private void write(String key, LinkedHashSet<String> values) {
        mPrefs.edit().putString(key, TextUtils.join("\n", values)).apply();
    }

    private static void trim(LinkedHashSet<String> set, int max) {
        if (set.size() <= max) {
            return;
        }
        List<String> list = new ArrayList<>(set);
        set.clear();
        set.addAll(list.subList(list.size() - max, list.size()));
    }
}
