package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.discover.ZappingManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zapping Plus overlay, PSP XMB style:
 * categories scroll horizontally on top, videos scroll vertically on the left.
 * It disappears quickly, so it stays out of the way of the video.
 */
public class ZappingOverlay implements ZappingManager.Listener {
    private static final long HIDE_DELAY_MS = 2600;
    private static final int VISIBLE_ITEMS = 2; // above and below the current one
    private final Activity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<Integer> mConsumedKeys = new HashSet<>();
    private final Runnable mHide = this::hide;
    private FrameLayout mRoot;
    private TextView[] mCategoryViews;
    private LinearLayout mColumn;
    private boolean mVisible;

    public interface PlayerState {
        boolean isControlsOverlayShown();
    }

    public ZappingOverlay(Activity activity) {
        mActivity = activity;
    }

    // ---------------- lifecycle ----------------

    public void onResume() {
        ZappingManager.instance().setListener(this);
        if (ZappingManager.instance().isActive()) {
            ZappingManager.instance().syncCursor();
            show();
        }
    }

    public void onPause() {
        ZappingManager.instance().cancelPending();
        ZappingManager.instance().setListener(null);
        hideNow();
    }

    @Override
    public void onCursorChanged() {
        if (ZappingManager.instance().isActive()) {
            show();
        }
    }

    // ---------------- keys ----------------

    /**
     * @return true if the key was used by the zapping
     */
    public boolean dispatchKeyEvent(KeyEvent event, PlayerState player) {
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            return mConsumedKeys.remove(keyCode);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        ZappingManager zapping = ZappingManager.instance();

        if (!zapping.isActive()) {
            hideNow();
            return false;
        }

        // Player controls are visible: behave like the normal player
        if (player != null && player.isControlsOverlayShown()) {
            hideNow();
            return false;
        }

        boolean handled = false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                prepareCursor();
                zapping.moveItem(-1);
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                prepareCursor();
                zapping.moveItem(1);
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                prepareCursor();
                zapping.moveCategory(-1);
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                prepareCursor();
                zapping.moveCategory(1);
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (mVisible) {
                    if (zapping.isCursorOnPlaying()) {
                        hideNow();
                    } else {
                        zapping.playCursorNow();
                    }
                    handled = true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (mVisible) {
                    zapping.cancelPending();
                    hideNow();
                    handled = true;
                }
                break;
        }

        if (handled) {
            mConsumedKeys.add(keyCode);
        }

        return handled;
    }

    /** First key press after the overlay was hidden: start from the playing video. */
    private void prepareCursor() {
        if (!mVisible) {
            ZappingManager.instance().syncCursor();
        }
    }

    // ---------------- view ----------------

    private void show() {
        ensureViews();
        render();
        if (!mVisible) {
            mVisible = true;
            mRoot.setVisibility(View.VISIBLE);
            mRoot.animate().cancel();
            mRoot.setAlpha(0f);
            mRoot.animate().alpha(1f).setDuration(120).start();
        }
        mHandler.removeCallbacks(mHide);
        mHandler.postDelayed(mHide, HIDE_DELAY_MS);
    }

    private void hide() {
        if (mRoot == null || !mVisible) {
            return;
        }
        mVisible = false;
        mRoot.animate().cancel();
        mRoot.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (!mVisible && mRoot != null) {
                mRoot.setVisibility(View.GONE);
            }
        }).start();
    }

    private void hideNow() {
        mHandler.removeCallbacks(mHide);
        if (mRoot == null) {
            return;
        }
        mVisible = false;
        mRoot.animate().cancel();
        mRoot.setVisibility(View.GONE);
    }

    private void ensureViews() {
        if (mRoot != null) {
            return;
        }

        ViewGroup content = mActivity.findViewById(android.R.id.content);
        mRoot = new FrameLayout(mActivity);
        mRoot.setVisibility(View.GONE);
        mRoot.setFocusable(false);
        mRoot.setClickable(false);

        // Left shade for the column
        View leftShade = new View(mActivity);
        leftShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x99000000, 0x00000000}));
        mRoot.addView(leftShade, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.MATCH_PARENT));

        // Top shade for the categories
        View topShade = new View(mActivity);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xE6000000, 0x00000000}));
        mRoot.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        // Categories (horizontal)
        LinearLayout categories = new LinearLayout(mActivity);
        categories.setOrientation(LinearLayout.HORIZONTAL);
        categories.setGravity(Gravity.CENTER);
        mCategoryViews = new TextView[5];
        for (int i = 0; i < mCategoryViews.length; i++) {
            TextView view = new TextView(mActivity);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setTextColor(Color.WHITE);
            view.setShadowLayer(8, 0, 2, Color.BLACK);
            view.setPadding(dp(22), 0, dp(22), 0);
            boolean center = i == 2;
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, center ? 30 : 18);
            view.setTypeface(Typeface.DEFAULT, center ? Typeface.BOLD : Typeface.NORMAL);
            view.setAlpha(center ? 1f : (i == 1 || i == 3 ? 0.55f : 0.3f));
            mCategoryViews[i] = view;
            categories.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        FrameLayout.LayoutParams catParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        catParams.topMargin = dp(34);
        mRoot.addView(categories, catParams);

        // Videos (vertical)
        mColumn = new LinearLayout(mActivity);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams colParams = new FrameLayout.LayoutParams(dp(600), ViewGroup.LayoutParams.MATCH_PARENT);
        colParams.leftMargin = dp(48);
        colParams.topMargin = dp(90);
        colParams.bottomMargin = dp(50);
        mRoot.addView(mColumn, colParams);

        // Hint
        TextView hint = new TextView(mActivity);
        hint.setText(com.liskovsoft.smartyoutubetv2.common.R.string.zapping_hint);
        hint.setTextColor(Color.WHITE);
        hint.setAlpha(0.7f);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setShadowLayer(6, 0, 1, Color.BLACK);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hintParams.leftMargin = dp(48);
        hintParams.bottomMargin = dp(24);
        mRoot.addView(hint, hintParams);

        content.addView(mRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void render() {
        ZappingManager zapping = ZappingManager.instance();
        int count = zapping.getCategoryCount();
        if (count == 0) {
            return;
        }

        int cat = zapping.getCursorCat();
        for (int i = 0; i < mCategoryViews.length; i++) {
            int offset = i - 2;
            TextView view = mCategoryViews[i];
            // Hide repeated categories when there are only a few
            boolean show = offset == 0 || Math.abs(offset) * 2 < count + (offset > 0 ? 1 : 0);
            view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            String title = zapping.getCategoryTitle(cat + offset);
            view.setText(offset == 0 ? "‹  " + title + "  ›" : title);
        }

        mColumn.removeAllViews();
        List<Video> list = zapping.getCategory(cat);
        int size = list.size();
        int current = zapping.getCursorItem();
        boolean playingCategory = cat == zapping.getPlayingCat();

        for (int offset = -VISIBLE_ITEMS; offset <= VISIBLE_ITEMS; offset++) {
            if (size < 2 * VISIBLE_ITEMS + 1 && Math.abs(offset) * 2 >= size + (offset > 0 ? 1 : 0) && offset != 0) {
                continue;
            }
            int index = ((current + offset) % size + size) % size;
            boolean playing = playingCategory && index == zapping.getPlayingItem();
            mColumn.addView(createRow(list.get(index), offset == 0, playing));
        }
    }

    private View createRow(Video video, boolean selected, boolean playing) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setAlpha(selected ? 1f : 0.55f);

        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(2), 0xCCFFFFFF);
            row.setBackground(bg);
        }

        int thumbWidth = selected ? dp(208) : dp(120);
        int thumbHeight = thumbWidth * 9 / 16;
        ImageView thumb = new ImageView(mActivity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF202020);
        if (!TextUtils.isEmpty(video.cardImageUrl)) {
            Glide.with(mActivity).load(video.cardImageUrl).into(thumb);
        }
        row.addView(thumb, new LinearLayout.LayoutParams(thumbWidth, thumbHeight));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);

        TextView title = new TextView(mActivity);
        String prefix = playing ? "▶ " : (video.isShorts ? "▮ " : "");
        title.setText(prefix + (video.title != null ? video.title : ""));
        title.setTextColor(Color.WHITE);
        title.setMaxLines(selected ? 3 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, selected ? 20 : 15);
        title.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        title.setShadowLayer(6, 0, 1, Color.BLACK);
        texts.addView(title);

        CharSequence second = video.getAuthor() != null ? video.getAuthor() : video.secondTitle;
        if (selected && second != null) {
            TextView author = new TextView(mActivity);
            author.setText(second);
            author.setTextColor(0xFFDDDDDD);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            author.setPadding(0, dp(4), 0, 0);
            texts.addView(author);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        row.setLayoutParams(params);
        return row;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics());
    }
}
