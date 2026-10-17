package com.liskovsoft.smartyoutubetv2.common.discover;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Alternative recommendation engine for the "Scopri" section.
 * Uses only data the app already has access to: watch history, related videos, search, shorts feed.
 */
public class DiscoverEngine {
    private static final String TAG = DiscoverEngine.class.getSimpleName();
    /** Section id. Far away from YouTube MediaGroup types and from pinned items ids. */
    public static final int SECTION_ID = 1_000_001;
    public static final String ACTION_SHUFFLE = "__discover_shuffle";
    public static final String ACTION_ZAPPING = "__discover_zapping";

    public static final String ROW_FOR_YOU = "Per te, mai visti";
    public static final String ROW_DEEP = "Scavo profondo";
    public static final String ROW_DIFFERENT = "Totalmente diverso";
    public static final String ROW_SHORTS = "Shorts a sorpresa";

    private static final int ROW_SIZE = 30;
    private static final int TIMEOUT_SEC = 25;

    private static final String[] TOPICS = {
            "documentario fondali oceanici", "restauro mobili antichi", "storia dei videogiochi", "astronomia spiegata semplice",
            "cucina giapponese tradizionale", "falegnameria giapponese", "vita in un faro", "come funzionano gli orologi meccanici",
            "fisica quantistica spiegata", "viaggio in treno transiberiana", "artigiani che scompaiono", "origami avanzato",
            "street food asiatico", "vulcani documentario", "storia di roma antica", "archeologia scoperte recenti",
            "costruire una barca in legno", "vita in Antartide", "musica tradizionale mongola", "canto armonico tuvano",
            "lavorazione del vetro soffiato", "forgiatura coltelli", "giardini giapponesi", "case minuscole tiny house",
            "esplorazione grotte speleologia", "animali degli abissi", "uccelli rari documentario", "orchestra dietro le quinte",
            "jazz anni 50 live", "storia della moda", "come si fa la carta a mano", "illusioni ottiche spiegate",
            "matematica bellezza", "scacchi partite storiche", "Islanda viaggio", "Mongolia nomadi", "Giappone rurale",
            "montagne Dolomiti trekking", "cucina siciliana nonna", "panificazione lievito madre", "formaggi di montagna",
            "apicoltura", "orto sinergico", "trucchi di magia svelati", "storia dell'aviazione", "treni a vapore",
            "esperimenti di chimica", "robotica fai da te", "elettronica vintage riparazione", "restauro auto d'epoca",
            "architettura brutalista", "città abbandonate", "storia dei pirati", "misteri archeologici",
            "lingue rare del mondo", "calligrafia", "pittura ad acquerello timelapse", "scultura in pietra",
            "danza tradizionale africana", "flamenco", "tango argentino", "musica barocca", "lavorazione della ceramica raku",
            "vita su una nave cargo", "pesca tradizionale", "sopravvivenza nella natura", "bushcraft rifugio",
            "neuroscienze cervello", "psicologia esperimenti famosi", "filosofia spiegata", "storia della medicina",
            "micro mondo al microscopio", "insetti macro", "piante carnivore", "funghi documentario",
            "spazio missioni Apollo", "telescopio James Webb immagini", "meteorologia tempeste", "aurora boreale timelapse",
            "fumetto come si disegna", "animazione stop motion", "cinema dietro le quinte effetti speciali", "sound design",
            "strumenti musicali strani", "costruire una chitarra", "orologiaio", "rilegatura libri a mano",
            "cultura coreana", "India viaggio treno", "Perù Ande", "Marocco deserto", "Norvegia fiordi",
            "storia del caffè", "tè cinese cerimonia", "cioccolato artigianale", "vino come si fa",
            "skateboard documentario", "arrampicata big wall", "vela oceanica in solitario", "ciclismo epico",
            "sumo", "kendo", "parkour", "freediving apnea"
    };

    public static class DiscoverRow {
        public final String title;
        public final List<Video> videos;

        DiscoverRow(String title, List<Video> videos) {
            this.title = title;
            this.videos = videos;
        }
    }

    private final Context mContext;
    private final Random mRandom = new Random();

    public DiscoverEngine(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Emits every row as soon as it's ready. Runs on background threads, delivers on main thread.
     */
    public Observable<DiscoverRow> getRowsObserve() {
        return Observable.<DiscoverRow>create(emitter -> {
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                Context ctx = mContext;
                DiscoverStore store = DiscoverStore.instance(ctx);
                Set<String> played = store.getPlayed();
                Set<String> shown = store.getShown();

                List<MediaItem> history = loadHistory();
                Set<String> watched = new HashSet<>(played);
                Map<String, Integer> channelFreq = new HashMap<>();
                for (MediaItem item : history) {
                    if (item.getVideoId() != null) {
                        watched.add(item.getVideoId());
                    }
                    if (item.getChannelId() != null) {
                        Integer count = channelFreq.get(item.getChannelId());
                        channelFreq.put(item.getChannelId(), count == null ? 1 : count + 1);
                    }
                }

                Set<String> used = new HashSet<>(); // no duplicates between rows

                // 1. Your taste, never seen
                if (!emitter.isDisposed() && !history.isEmpty()) {
                    List<Video> forYou = buildForYou(pool, history, watched, shown, channelFreq, used);
                    emit(emitter, store, ROW_FOR_YOU, forYou);
                }

                // 2. Deep dig: random walks across related videos, landing on unknown channels
                if (!emitter.isDisposed() && !history.isEmpty()) {
                    List<Video> deep = buildDeep(pool, history, watched, shown, channelFreq.keySet(), used);
                    emit(emitter, store, ROW_DEEP, deep);
                }

                // 3. Totally different
                if (!emitter.isDisposed()) {
                    List<Video> different = buildDifferent(pool, store, watched, shown, channelFreq.keySet(), used);
                    emit(emitter, store, ROW_DIFFERENT, different);
                }

                // 4. Shorts (vertical) for the zapping
                if (!emitter.isDisposed()) {
                    List<Video> shorts = buildShorts(watched, used);
                    emit(emitter, store, ROW_SHORTS, shorts);
                }

                emitter.onComplete();
            } catch (Throwable e) {
                Log.e(TAG, "Discover error: %s", e.getMessage());
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            } finally {
                pool.shutdownNow();
            }
        }).subscribeOn(Schedulers.newThread())
          .observeOn(AndroidSchedulers.mainThread());
    }

    private static void emit(io.reactivex.ObservableEmitter<DiscoverRow> emitter, DiscoverStore store, String title, List<Video> videos) {
        if (emitter.isDisposed() || videos == null || videos.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Video video : videos) {
            ids.add(video.videoId);
        }
        store.markShown(ids);
        emitter.onNext(new DiscoverRow(title, videos));
    }

    // ---------------- rows ----------------

    private List<Video> buildForYou(ExecutorService pool, List<MediaItem> history, Set<String> watched, Set<String> shown,
                                    Map<String, Integer> channelFreq, Set<String> used) {
        List<MediaItem> seeds = pickSeeds(history, 7);
        List<List<MediaItem>> related = fetchRelatedParallel(pool, seeds);

        // Interleave the results of every seed, so no single topic dominates
        List<MediaItem> candidates = interleave(related);
        Map<String, Integer> perChannel = new HashMap<>();
        List<Video> result = new ArrayList<>();
        List<Video> fallback = new ArrayList<>();

        for (MediaItem item : candidates) {
            if (!isPlayable(item, false) || watched.contains(item.getVideoId()) || used.contains(item.getVideoId())) {
                continue;
            }
            String channel = item.getChannelId();
            Integer freq = channel != null ? channelFreq.get(channel) : null;
            int limit = freq != null && freq >= 3 ? 1 : 2; // channels you watch a lot: max 1 video
            Integer count = perChannel.get(String.valueOf(channel));
            if (count != null && count >= limit) {
                continue;
            }
            perChannel.put(String.valueOf(channel), count == null ? 1 : count + 1);

            Video video = toVideo(item);
            if (shown.contains(item.getVideoId())) {
                fallback.add(video); // proposed recently: use only if needed
            } else {
                result.add(video);
            }
        }

        Collections.shuffle(result, mRandom);
        fill(result, fallback);
        return take(result, used);
    }

    private List<Video> buildDeep(ExecutorService pool, List<MediaItem> history, Set<String> watched, Set<String> shown,
                                  Set<String> knownChannels, Set<String> used) {
        List<MediaItem> seeds = pickSeeds(history, 5);
        List<Future<List<MediaItem>>> walks = new ArrayList<>();

        for (MediaItem seed : seeds) {
            walks.add(pool.submit(() -> randomWalk(seed, 3, knownChannels, watched)));
        }

        List<List<MediaItem>> lists = new ArrayList<>();
        for (Future<List<MediaItem>> walk : walks) {
            try {
                lists.add(walk.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (Exception e) {
                Log.e(TAG, "Walk failed: %s", e.getMessage());
            }
        }

        List<MediaItem> candidates = interleave(lists);
        Set<String> channels = new HashSet<>();
        List<Video> result = new ArrayList<>();
        List<Video> fallback = new ArrayList<>();

        for (MediaItem item : candidates) {
            if (!isPlayable(item, false) || watched.contains(item.getVideoId()) || used.contains(item.getVideoId())) {
                continue;
            }
            String channel = item.getChannelId();
            if (channel != null && (knownChannels.contains(channel) || !channels.add(channel))) {
                continue; // only channels you don't know, one video each
            }
            (shown.contains(item.getVideoId()) ? fallback : result).add(toVideo(item));
        }

        Collections.shuffle(result, mRandom);
        fill(result, fallback);
        return take(result, used);
    }

    /**
     * Follows the related videos chain for a few hops, preferring unknown channels at every hop.
     * Returns the related videos found at the last hop.
     */
    private List<MediaItem> randomWalk(MediaItem seed, int depth, Set<String> knownChannels, Set<String> watched) {
        String currentId = seed.getVideoId();
        List<MediaItem> last = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(currentId);

        for (int hop = 0; hop < depth && currentId != null; hop++) {
            List<MediaItem> related = fetchRelated(currentId);
            if (related.isEmpty()) {
                break;
            }
            last = related;

            List<MediaItem> unknown = new ArrayList<>();
            List<MediaItem> any = new ArrayList<>();
            for (MediaItem item : related) {
                if (!isPlayable(item, false) || visited.contains(item.getVideoId())) {
                    continue;
                }
                any.add(item);
                if (item.getChannelId() != null && !knownChannels.contains(item.getChannelId()) && !watched.contains(item.getVideoId())) {
                    unknown.add(item);
                }
            }
            List<MediaItem> pool = !unknown.isEmpty() ? unknown : any;
            if (pool.isEmpty()) {
                break;
            }
            // Skip the first results (the most obvious ones) when possible
            int from = pool.size() > 6 ? 3 : 0;
            MediaItem next = pool.get(from + mRandom.nextInt(pool.size() - from));
            currentId = next.getVideoId();
            visited.add(currentId);
        }

        return last;
    }

    private List<Video> buildDifferent(ExecutorService pool, DiscoverStore store, Set<String> watched, Set<String> shown,
                                       Set<String> knownChannels, Set<String> used) {
        Set<String> recentTopics = store.getRecentTopics();
        List<String> topics = new ArrayList<>();
        for (String topic : TOPICS) {
            if (!recentTopics.contains(topic)) {
                topics.add(topic);
            }
        }
        if (topics.size() < 6) {
            Collections.addAll(topics, TOPICS);
        }
        Collections.shuffle(topics, mRandom);
        List<String> chosen = topics.subList(0, Math.min(6, topics.size()));

        List<Future<List<MediaItem>>> searches = new ArrayList<>();
        for (String topic : chosen) {
            store.markTopic(topic);
            searches.add(pool.submit(() -> search(topic)));
        }

        List<List<MediaItem>> lists = new ArrayList<>();
        for (Future<List<MediaItem>> search : searches) {
            try {
                List<MediaItem> items = search.get(TIMEOUT_SEC, TimeUnit.SECONDS);
                // Max 5 for each topic, so the row is really mixed
                List<MediaItem> picked = new ArrayList<>();
                for (MediaItem item : items) {
                    if (picked.size() >= 5) {
                        break;
                    }
                    if (isPlayable(item, false) && !watched.contains(item.getVideoId()) && !shown.contains(item.getVideoId())
                            && (item.getChannelId() == null || !knownChannels.contains(item.getChannelId()))) {
                        picked.add(item);
                    }
                }
                lists.add(picked);
            } catch (Exception e) {
                Log.e(TAG, "Search failed: %s", e.getMessage());
            }
        }

        List<Video> result = new ArrayList<>();
        for (MediaItem item : interleave(lists)) {
            if (!used.contains(item.getVideoId())) {
                result.add(toVideo(item));
            }
        }
        return take(result, used);
    }

    private List<Video> buildShorts(Set<String> watched, Set<String> used) {
        List<Video> result = new ArrayList<>();
        try {
            ContentService service = getContentService();
            MediaGroup group = null;
            try {
                group = service.getShortsObserve().blockingFirst();
            } catch (Throwable e) {
                Log.e(TAG, "Shorts feed empty: %s", e.getMessage());
            }
            for (int page = 0; page < 3 && group != null; page++) {
                if (group.getMediaItems() != null) {
                    for (MediaItem item : group.getMediaItems()) {
                        if (item.getVideoId() != null && !watched.contains(item.getVideoId()) && !used.contains(item.getVideoId())) {
                            Video video = toVideo(item);
                            video.isShorts = true;
                            result.add(video);
                        }
                    }
                }
                if (result.size() >= ROW_SIZE || group.getNextPageKey() == null) {
                    break;
                }
                group = service.continueGroup(group);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Shorts failed: %s", e.getMessage());
        }
        Collections.shuffle(result, mRandom);
        return take(result, used);
    }

    // ---------------- data helpers ----------------

    private List<MediaItem> loadHistory() {
        List<MediaItem> result = new ArrayList<>();
        try {
            ContentService service = getContentService();
            MediaGroup group = service.getHistory();
            // First pages of history are enough to know your taste
            for (int page = 0; page < 3 && group != null; page++) {
                if (group.getMediaItems() != null) {
                    for (MediaItem item : group.getMediaItems()) {
                        if (item.getVideoId() != null) {
                            result.add(item);
                        }
                    }
                }
                if (group.getNextPageKey() == null) {
                    break;
                }
                group = service.continueGroup(group);
            }
        } catch (Throwable e) {
            Log.e(TAG, "History failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Random seeds, weighted towards recent history but never only the last videos.
     */
    private List<MediaItem> pickSeeds(List<MediaItem> history, int count) {
        List<MediaItem> seeds = new ArrayList<>();
        if (history.isEmpty()) {
            return seeds;
        }
        Set<String> channels = new HashSet<>();
        List<MediaItem> copy = new ArrayList<>(history);
        int attempts = 0;
        while (seeds.size() < count && !copy.isEmpty() && attempts++ < 200) {
            // Square of a random number: more often recent items, sometimes old ones
            double r = mRandom.nextDouble();
            int index = (int) (r * r * copy.size());
            MediaItem item = copy.remove(index);
            // Different channels for the seeds = more variety
            if (item.getChannelId() == null || channels.add(item.getChannelId()) || copy.size() < count) {
                seeds.add(item);
            }
        }
        return seeds;
    }

    private List<List<MediaItem>> fetchRelatedParallel(ExecutorService pool, List<MediaItem> seeds) {
        List<Future<List<MediaItem>>> futures = new ArrayList<>();
        for (MediaItem seed : seeds) {
            final String id = seed.getVideoId();
            futures.add(pool.submit((Callable<List<MediaItem>>) () -> fetchRelated(id)));
        }
        List<List<MediaItem>> result = new ArrayList<>();
        for (Future<List<MediaItem>> future : futures) {
            try {
                result.add(future.get(TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (Exception e) {
                Log.e(TAG, "Related failed: %s", e.getMessage());
            }
        }
        return result;
    }

    private List<MediaItem> fetchRelated(String videoId) {
        List<MediaItem> result = new ArrayList<>();
        try {
            MediaItemMetadata metadata = getMediaItemService().getMetadata(videoId);
            if (metadata != null && metadata.getSuggestions() != null) {
                for (MediaGroup group : metadata.getSuggestions()) {
                    if (group != null && group.getMediaItems() != null) {
                        result.addAll(group.getMediaItems());
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Metadata failed: %s", e.getMessage());
        }
        return result;
    }

    private List<MediaItem> search(String query) {
        List<MediaItem> result = new ArrayList<>();
        try {
            List<MediaGroup> groups = getContentService().getSearch(query);
            if (groups != null) {
                for (MediaGroup group : groups) {
                    if (group != null && group.getMediaItems() != null) {
                        result.addAll(group.getMediaItems());
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Search failed: %s", e.getMessage());
        }
        return result;
    }

    private static boolean isPlayable(MediaItem item, boolean allowShorts) {
        if (item == null || TextUtils.isEmpty(item.getVideoId())) {
            return false;
        }
        if (item.isLive() || item.isUpcoming()) {
            return false;
        }
        if (!allowShorts && item.isShorts()) {
            return false;
        }
        return item.getPercentWatched() <= 0;
    }

    private static List<MediaItem> interleave(List<List<MediaItem>> lists) {
        LinkedHashMap<String, MediaItem> result = new LinkedHashMap<>();
        int max = 0;
        for (List<MediaItem> list : lists) {
            max = Math.max(max, list.size());
        }
        for (int i = 0; i < max; i++) {
            for (List<MediaItem> list : lists) {
                if (i < list.size()) {
                    MediaItem item = list.get(i);
                    if (item != null && item.getVideoId() != null && !result.containsKey(item.getVideoId())) {
                        result.put(item.getVideoId(), item);
                    }
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private static void fill(List<Video> result, List<Video> fallback) {
        for (Video video : fallback) {
            if (result.size() >= ROW_SIZE) {
                break;
            }
            result.add(video);
        }
    }

    private static List<Video> take(List<Video> videos, Set<String> used) {
        List<Video> result = new ArrayList<>();
        for (Video video : videos) {
            if (result.size() >= ROW_SIZE) {
                break;
            }
            if (used.add(video.videoId)) {
                result.add(video);
            }
        }
        return result;
    }

    private static Video toVideo(MediaItem item) {
        return Video.from(item);
    }

    private static ContentService getContentService() {
        return YouTubeServiceManager.instance().getContentService();
    }

    private static MediaItemService getMediaItemService() {
        return YouTubeServiceManager.instance().getMediaItemService();
    }
}
