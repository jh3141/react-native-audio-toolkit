package com.futurice.rctaudiotoolkit;

import android.content.Context;
import android.media.AudioManager;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;
import android.net.Uri;
import android.content.ContextWrapper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory;
import com.google.android.exoplayer2.source.DefaultMediaSourceEventListener;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.CacheUtil;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.IOException;
import java.io.File;
import java.lang.Thread;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import okhttp3.OkHttpClient;

public class AudioPlayerModule extends ReactContextBaseJavaModule implements LifecycleEventListener, AudioManager.OnAudioFocusChangeListener {
    private static final String LOG_TAG = "AudioPlayerModule";
    private static final long DEFAULT_MAX_CACHE_SIZE = 20*1024*1024L;
    private final OkHttpDataSourceFactory uncachedDataSourceFactory;

    Map<Integer, SimpleExoPlayer> playerPool = new HashMap<>();
    Map<Integer, Boolean> playerAutoDestroy = new HashMap<>();
    Map<Integer, Boolean> playerContinueInBackground = new HashMap<>();
    Map<Integer, Callback> playerSeekCallback = new HashMap<>();

    boolean looping = false;
    private ReactApplicationContext context;
    private AudioManager mAudioManager;
    private Integer lastPlayerId;
    private DataSource.Factory dataSourceFactory;
    private Handler handler;
    private LeastRecentlyUsedCacheEvictor cacheEvictor;
    private SimpleCache cache;

    public AudioPlayerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.context = reactContext;
        reactContext.addLifecycleEventListener(this);
        this.mAudioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        cacheEvictor = new LeastRecentlyUsedCacheEvictor(DEFAULT_MAX_CACHE_SIZE);

        cache = new SimpleCache(
                new File(context.getExternalCacheDir(), "exoplayer-cache"),
                cacheEvictor);

        uncachedDataSourceFactory = new OkHttpDataSourceFactory(
                new OkHttpClient(),
                "Android.ExoPlayer",
                null);
        dataSourceFactory =
                new CacheDataSourceFactory(
                        cache,
                        uncachedDataSourceFactory);

        handler = new Handler(reactContext.getMainLooper());    // handler is used to handle messages on the main thread
    }

    private SimpleExoPlayer newPlayer ()
    {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector();
        return ExoPlayerFactory.newSimpleInstance(context, trackSelector);
    }

    @Override
    public void onHostResume() {
        // Activity `onResume`
    }

    @Override
    public void onHostPause() {
        /*
        for (Map.Entry<Integer, MediaPlayer> entry : this.playerPool.entrySet()) {
            Integer playerId = entry.getKey();

            if (!this.playerContinueInBackground.get(playerId)) {
                MediaPlayer player = entry.getValue();
                player.pause();

                WritableMap info = getInfo(player);

                WritableMap data = new WritableNativeMap();
                data.putString("message", "Playback paused due to onHostPause");
                data.putMap("info", info);

                emitEvent(playerId, "pause", data);
            }
        }
        */
    }

    @Override
    public void onHostDestroy() {
        // Activity `onDestroy`
        try {
            cache.release();
        } catch (Cache.CacheException e) {
            Log.e(LOG_TAG, "Failed to release cache", e);
        }
    }

    @Override
    public String getName() {
        return "RCTAudioPlayer";
    }

    private void emitEvent(Integer playerId, String event, WritableMap data) {
        WritableMap payload = new WritableNativeMap();
        payload.putString("event", event);
        payload.putMap("data", data);

        this.context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("RCTAudioPlayerEvent:" + playerId, payload);
    }

    private void emitMessageEvent (Integer playerId, String event, String message)
    {
        WritableMap data = new WritableNativeMap();
        data.putString("message", message);
        emitEvent(playerId, event, data);
    }

    private WritableMap errObj(final String code, final String message, final boolean enableLog) {
        WritableMap err = Arguments.createMap();

        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String stackTraceString = "";

        for (StackTraceElement e : stackTrace) {
            stackTraceString += e.toString() + "\n";
        }

        err.putString("err", code);
        err.putString("message", message);

        if (enableLog) {
            err.putString("stackTrace", stackTraceString);
            Log.e(LOG_TAG, message);
            Log.d(LOG_TAG, stackTraceString);
        }

        return err;
    }

    private WritableMap errObj(final String code, final String message) {
        return errObj(code, message, true);
    }

    private Uri uriFromPath(String path) {
        File file = null;
        String fileNameWithoutExt;
        String extPath;

        // Try finding file in app data directory
        extPath = new ContextWrapper(this.context).getFilesDir() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file on sdcard
        extPath = Environment.getExternalStorageDirectory() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file by full path
        file = new File(path);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file in Android "raw" resources
        if (path.lastIndexOf('.') != -1) {
            fileNameWithoutExt = path.substring(0, path.lastIndexOf('.'));
        } else {
            fileNameWithoutExt = path;
        }

        int resId = this.context.getResources().getIdentifier(fileNameWithoutExt,
            "raw", this.context.getPackageName());
        if (resId != 0) {
            return Uri.parse("android.resource://" + this.context.getPackageName() + "/" + resId);
        }

        // Otherwise pass whole path string as URI and hope for the best
        return Uri.parse(path);
    }

    @ReactMethod
    public void destroy(Integer playerId, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);

        if (player != null) {
            player.release();
            this.playerPool.remove(playerId);
            this.playerAutoDestroy.remove(playerId);
            this.playerContinueInBackground.remove(playerId);
            this.playerSeekCallback.remove(playerId);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Destroyed player");

            emitEvent(playerId, "info", data);
        }

        if (callback != null) {
            callback.invoke();
        }
    }

    private void destroy(Integer playerId) {
        this.destroy(playerId, null);
    }

    @ReactMethod
    public void seek(Integer playerId, Integer position, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        if (position >= 0) {
            Callback oldCallback = this.playerSeekCallback.get(playerId);

            if (oldCallback != null) {
                oldCallback.invoke(errObj("seekfail", "new seek operation before old one completed", false));
                this.playerSeekCallback.remove(playerId);
            }

            this.playerSeekCallback.put(playerId, callback);
            player.seekTo(position);
        }
    }

    private WritableMap getInfo(SimpleExoPlayer player) {
        WritableMap info = Arguments.createMap();

        info.putDouble("duration", player.getDuration());
        info.putDouble("position", player.getCurrentPosition());
        info.putInt("audioSessionId", player.getAudioSessionId());
        info.putDouble("bufferedPercentage", player.getBufferedPercentage());
        info.putDouble("bufferedPosition", player.getBufferedPosition());
        info.putInt("playbackState", player.getPlaybackState());

        return info;
    }

    @ReactMethod
    public void prepare(final Integer playerId, String path, ReadableMap options, final Callback callback) {
        if (path == null || path.isEmpty()) {
            callback.invoke(errObj("nopath", "Provided path was empty"));
            return;
        }

        // Release old player if exists
        destroy(playerId);
        this.lastPlayerId = playerId;

        final Uri uri = uriFromPath(path);

        final SimpleExoPlayer player = newPlayer();

        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(getUsageType (options))
            .setContentType(getContentType (options))
            .build();

        player.setAudioAttributes(attributes);

        Log.d(LOG_TAG, uri.getPath());
        MediaSource mediaSource = createMediaSource(uri, options);
        mediaSource.addEventListener(handler, new DefaultMediaSourceEventListener() {
            @Override
            public void onLoadError(int windowIndex, @Nullable MediaSource.MediaPeriodId mediaPeriodId, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
                Log.i(LOG_TAG, "media load error for " + uri + ": " + error);
                emitMessageEvent(playerId, "warning", "IO Error loading content: " + error.getMessage());
            }
        });
        player.addListener(new Player.DefaultEventListener() {
            boolean preparing = true;
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState)
            {
                Log.i(LOG_TAG, "player " + uri + " state change " + playbackState + ", " + playWhenReady);
                switch (playbackState) {
                    case Player.STATE_READY:
                        if (preparing) {
                            preparing = false;
                            callback.invoke(null, getInfo(player));
                        }
                        break;
                    case Player.STATE_ENDED:
                        player.seekTo(0);
                        emitMessageEvent(playerId, "ended", "playback completed");
                        if (playerAutoDestroy.get(playerId)) {
                            Log.d(LOG_TAG, "onPlayerStateChanged(STATE_ENDED): Autodestroying player...");
                            destroy(playerId);
                        }
                        break;
                    case Player.STATE_BUFFERING:
                        onLoadingChanged(true);
                        break;
                }
            }

            @Override
            public void onLoadingChanged(boolean isLoading) {
                WritableMap data = new WritableNativeMap();
                data.putBoolean("loading", isLoading);
                data.putInt("buffered", player.getBufferedPercentage());
                emitEvent(playerId, "loading", data);
            }

            @Override
            public void onSeekProcessed()
            {
                Callback callback = playerSeekCallback.get(playerId);
                if (callback != null)
                {
                    callback.invoke(null, getInfo(player));
                    playerSeekCallback.remove(playerId);
                }

                emitMessageEvent (playerId, "seeked", "Seek operation completed");
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                Log.i(LOG_TAG, "Playback error for " + uri + ": " + error.getMessage(), error);
                emitMessageEvent(playerId, "error", "Playback error: " + error.getMessage());
                destroy(playerId);
            }
        });

        /*
        player.setOnErrorListener(this);
        player.setOnInfoListener(this);
        player.setOnCompletionListener(this);
        player.setOnSeekCompleteListener(this);
        */
        this.playerPool.put(playerId, player);

        // Auto destroy player by default
        boolean autoDestroy = true;

        if (options.hasKey("autoDestroy")) {
            autoDestroy = options.getBoolean("autoDestroy");
        }

        // Don't continue in background by default
        boolean continueInBackground = false;

        if (options.hasKey("continuesToPlayInBackground")) {
            continueInBackground = options.getBoolean("continuesToPlayInBackground");
        }

        this.playerAutoDestroy.put(playerId, autoDestroy);
        this.playerContinueInBackground.put(playerId, continueInBackground);

        try {
            player.prepare(mediaSource, true, true);
        } catch (Exception e) {
            callback.invoke(errObj("prepare", e.toString()));
        }
    }

    private MediaSource createMediaSource(Uri uri, ReadableMap options)
    {
        return new ExtractorMediaSource.Factory(dataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(128 * 1024)       // check whether to load more every 128KB
                //.setCustomCacheKey(options.hasKey("cacheKey") ? options.getString("cacheKey") : null)
                .setMinLoadableRetryCount(options.hasKey("retryCount") ? options.getInt("retryCount") : ExtractorMediaSource.MIN_RETRY_COUNT_DEFAULT_FOR_MEDIA)
                .createMediaSource(uri);
    }

    private int getUsageType(ReadableMap options)
    {
        if (!options.hasKey("usage") || options.getType("usage") != ReadableType.String) return C.USAGE_UNKNOWN;
        switch (options.getString("usage")) {
            case "media": return C.USAGE_MEDIA;
            case "voice": return C.USAGE_VOICE_COMMUNICATION;
            case "game": return C.USAGE_GAME;
            default: return C.USAGE_UNKNOWN;
        }
    }

    private int getContentType(ReadableMap options)
    {
        if (!options.hasKey("contentType") || options.getType("contentType") != ReadableType.String) return C.CONTENT_TYPE_UNKNOWN;
        switch (options.getString("usage")) {
            case "music": return C.CONTENT_TYPE_MUSIC;
            case "movie": return C.CONTENT_TYPE_MOVIE;
            case "voice": return C.CONTENT_TYPE_SPEECH;
            case "sonification": return C.CONTENT_TYPE_SONIFICATION;
            default: return C.CONTENT_TYPE_UNKNOWN;
        }
    }

    @ReactMethod
    public void set(Integer playerId, ReadableMap options, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        // FIXME how does exoplayer handle wakelocks?
        if (options.hasKey("wakeLock")) {
            // TODO: can we disable the wake lock also?
            if (options.getBoolean("wakeLock")) {
                //player.setWakeMode(this.context, PowerManager.PARTIAL_WAKE_LOCK);
            }
        }

        if (options.hasKey("autoDestroy")) {
            this.playerAutoDestroy.put(playerId, options.getBoolean("autoDestroy"));
        }

        if (options.hasKey("continuesToPlayInBackground")) {
            this.playerContinueInBackground.put(playerId, options.getBoolean("continuesToPlayInBackground"));
        }

        if (options.hasKey("volume") && !options.isNull("volume")) {
            double vol = options.getDouble("volume");
            player.setVolume((float)vol);
        }

        if (options.hasKey("looping") && !options.isNull("looping")) {
            boolean looping = options.getBoolean("looping");
            player.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }

        if ((options.hasKey("speed") || options.hasKey("pitch"))) {
            PlaybackParameters params = PlaybackParameters.DEFAULT;

            if (options.hasKey("speed") && !options.isNull("speed")) {
                params = new PlaybackParameters((float) options.getDouble("speed"));
            }

            if (options.hasKey("pitch") && !options.isNull("pitch")) {
                params = new PlaybackParameters(params.speed, (float) options.getDouble("pitch"));
            }

            if (options.hasKey("skipSilence") && !options.isNull("skipSilence")) {
                params = new PlaybackParameters(params.speed, params.pitch, options.getBoolean("skipSilence"));
            }
        }

        callback.invoke();
    }


    @ReactMethod
    public void play(Integer playerId, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            this.mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            player.setPlayWhenReady(true);

            callback.invoke(null, getInfo(player));
        } catch (Exception e) {
            callback.invoke(errObj("playback", e.toString()));
        }
    }

    @ReactMethod
    public void pause(Integer playerId, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            player.setPlayWhenReady(false);

            // FIXME this should be done when the player actually pauses, not immediately...
            WritableMap info = getInfo(player);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Playback paused");
            data.putMap("info", info);

            emitEvent(playerId, "pause", data);

            callback.invoke(null, getInfo(player));

        } catch (Exception e) {
            callback.invoke(errObj("pause", e.toString()));
        }
    }

    @ReactMethod
    public void stop(Integer playerId, Callback callback) {
        SimpleExoPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            if (this.playerAutoDestroy.get(playerId)) {
                player.setPlayWhenReady(false);
                Log.d(LOG_TAG, "stop(): Autodestroying player...");
                destroy(playerId);
                callback.invoke();
            } else {
                // "Fake" stopping on Android by pausing and seeking to 0 so
                // that we remain in prepared state
                Callback oldCallback = this.playerSeekCallback.get(playerId);

                if (oldCallback != null) {
                    oldCallback.invoke(errObj("seekfail", "Playback stopped before seek operation could finish"));
                    this.playerSeekCallback.remove(playerId);
                }

                this.playerSeekCallback.put(playerId, callback);

                player.seekTo(0);
                player.setPlayWhenReady(false);
            }
        } catch (Exception e) {
            callback.invoke(errObj("stop", e.toString()));
        }
    }

    // Find playerId matching player from playerPool
    private Integer getPlayerId(SimpleExoPlayer player) {
        for (Entry<Integer, SimpleExoPlayer> entry : playerPool.entrySet()) {
            if (equals(player, entry.getValue())) {
                return entry.getKey();
            }
        }

        return null;
    }



    // Audio Focus
    public void onAudioFocusChange(int focusChange)
    {
        switch (focusChange)
        {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                SimpleExoPlayer player = this.playerPool.get(this.lastPlayerId);
                if (player != null) {
                    player.setPlayWhenReady(false);
                    WritableMap data = new WritableNativeMap();
                    data.putString("message", "Lost audio focus, playback paused");
                    data.putMap("info", getInfo(player));

                    this.emitEvent(this.lastPlayerId, "forcePause", data);
                }
                break;
        }
    }

    @ReactMethod
    public void precacheItem(String path, ReadableMap options, final Callback callback)
    {
        final Uri uri = uriFromPath(path);
        new Thread (new Runnable () {
            public void run() {
                try {
                    CacheUtil.cache(
                            new DataSpec(uri, DataSpec.FLAG_ALLOW_CACHING_UNKNOWN_LENGTH | DataSpec.FLAG_ALLOW_GZIP),
                            cache,
                            uncachedDataSourceFactory.createDataSource(),
                            null,
                            null);
                } catch (IOException e) {
                    callback.invoke(false, "Error: " + e.getMessage());
                } catch (InterruptedException e) {
                    callback.invoke(false, "Download interrupted");
                }
                callback.invoke(true);
            }
        }).start();
    }

    // Utils
    public static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}
