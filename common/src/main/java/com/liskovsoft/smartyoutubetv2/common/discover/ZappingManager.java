package com.liskovsoft.smartyoutubetv2.common.discover;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import java.util.ArrayList;
import java.util.List;

/**
 * "Zapping Plus" (PSP XMB style) state.<br/>
 * Left/Right = category (a row of the "Scopri" section), Up/Down = video inside the category.<br/>
 * The video starts when you stop scrolling, like the TV Hero zapping.
 */
public class ZappingManager {
    public static final long PLAY_DELAY_MS = 700;
    private static ZappingManager sInstance;
    private final List<String> mTitles = new ArrayList<>();
    private final List<List<Video>> mLists = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Category and item currently playing */
    private int mPlayingCat = -1;
    private int mPlayingItem = -1;
    /** Category and item highlighted in the overlay */
    private int mCursorCat;
    private int mCursorItem;
    private Runnable mPendingPlay;
    private Context mContext;
    private Listener mListener;

    public interface Listener {
        void onCursorChanged();
    }

    public static synchronized ZappingManager instance() {
        if (sInstance == null) {
            sInstance = new ZappingManager();
        }
        return sInstance;
    }

    // ---------------- categories ----------------

    public void clearCategories() {
        mTitles.clear();
        mLists.clear();
        mPlayingCat = -1;
        mPlayingItem = -1;
    }

    public void addCategory(String title, List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        mTitles.add(title);
        mLists.add(new ArrayList<>(videos));
    }

    public int getCategoryCount() {
        return mTitles.size();
    }

    public String getCategoryTitle(int index) {
        return mTitles.get(wrap(index, mTitles.size()));
    }

    public List<Video> getCategory(int index) {
        return mLists.get(wrap(index, mLists.size()));
    }

    /**
     * Finds the category that contains the given video.
     */
    public int findCategory(Video video) {
        if (video == null) {
            return -1;
        }
        for (int i = 0; i < mLists.size(); i++) {
            if (indexOf(mLists.get(i), video) != -1) {
                return i;
            }
        }
        return -1;
    }

    // ---------------- state ----------------

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Zapping is on only while the playing video belongs to the zapping list.
     */
    public boolean isActive() {
        if (mPlayingCat < 0 || mPlayingCat >= mLists.size() || mContext == null) {
            return false;
        }
        Video current = PlaybackPresenter.instance(mContext).getVideo();
        if (current == null) {
            return true; // still loading
        }
        int index = indexOf(mLists.get(mPlayingCat), current);
        if (index == -1) {
            // Maybe autoplay moved to another category item
            int cat = findCategory(current);
            if (cat == -1) {
                return false;
            }
            mPlayingCat = cat;
            index = indexOf(mLists.get(cat), current);
        }
        mPlayingItem = index;
        return true;
    }

    public int getCursorCat() {
        return mCursorCat;
    }

    public int getCursorItem() {
        return mCursorItem;
    }

    public int getPlayingCat() {
        return mPlayingCat;
    }

    public int getPlayingItem() {
        return mPlayingItem;
    }

    public Video getCursorVideo() {
        List<Video> list = getCategory(mCursorCat);
        return list.get(wrap(mCursorItem, list.size()));
    }

    /** Puts the cursor back on the video that is playing. */
    public void syncCursor() {
        if (mPlayingCat >= 0) {
            mCursorCat = mPlayingCat;
            mCursorItem = Math.max(mPlayingItem, 0);
        }
    }

    // ---------------- actions ----------------

    public void start(Context context, int category, int item) {
        if (mLists.isEmpty()) {
            return;
        }
        mContext = context.getApplicationContext();
        mCursorCat = wrap(category, mLists.size());
        mCursorItem = wrap(item, getCategory(mCursorCat).size());
        playCursor();
    }

    /** Starts the zapping from a video clicked in the "Scopri" rows. */
    public boolean startFrom(Context context, Video video) {
        int cat = findCategory(video);
        if (cat == -1) {
            return false;
        }
        start(context, cat, indexOf(mLists.get(cat), video));
        return true;
    }

    /** Up/Down: next/previous video in the highlighted category. Starts after a short pause. */
    public void moveItem(int delta) {
        if (mLists.isEmpty()) {
            return;
        }
        List<Video> list = getCategory(mCursorCat);
        mCursorItem = wrap(mCursorItem + delta, list.size());
        notifyCursor();
        schedulePlay();
    }

    /** Left/Right: change category. The current video keeps playing until you choose. */
    public void moveCategory(int delta) {
        if (mLists.isEmpty()) {
            return;
        }
        cancelPending();
        mCursorCat = wrap(mCursorCat + delta, mLists.size());
        mCursorItem = mCursorCat == mPlayingCat ? Math.max(mPlayingItem, 0) : 0;
        notifyCursor();
    }

    /** OK on the overlay: play the highlighted video now. */
    public void playCursorNow() {
        cancelPending();
        playCursor();
    }

    public boolean isCursorOnPlaying() {
        return mCursorCat == mPlayingCat && mCursorItem == mPlayingItem;
    }

    private void schedulePlay() {
        cancelPending();
        mPendingPlay = () -> {
            mPendingPlay = null;
            if (!isCursorOnPlaying()) {
                playCursor();
            }
        };
        mHandler.postDelayed(mPendingPlay, PLAY_DELAY_MS);
    }

    public void cancelPending() {
        if (mPendingPlay != null) {
            mHandler.removeCallbacks(mPendingPlay);
            mPendingPlay = null;
        }
    }

    private void playCursor() {
        if (mContext == null || mLists.isEmpty()) {
            return;
        }
        List<Video> list = getCategory(mCursorCat);
        Video video = list.get(wrap(mCursorItem, list.size()));

        // The category becomes the queue: Next button and autoplay stay inside it
        Playlist playlist = Playlist.instance();
        playlist.clear();
        for (Video item : list) {
            item.fromQueue = false;
        }
        playlist.addAll(list);
        playlist.setCurrent(video);
        video.fromQueue = true; // don't let the loader trim the queue

        mPlayingCat = mCursorCat;
        mPlayingItem = mCursorItem;
        DiscoverStore.instance(mContext).markPlayed(video.videoId);
        PlaybackPresenter.instance(mContext).openVideo(video);
        notifyCursor();
    }

    private void notifyCursor() {
        if (mListener != null) {
            mListener.onCursorChanged();
        }
    }

    private static int indexOf(List<Video> list, Video video) {
        if (video == null || video.videoId == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (video.videoId.equals(list.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    private static int wrap(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return ((index % size) + size) % size;
    }
}
