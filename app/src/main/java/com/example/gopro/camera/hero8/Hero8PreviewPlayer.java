package com.example.gopro.camera.hero8;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.PlayerView;

/** Media3 renderer for the clean MPEG-TS bytes supplied by {@link Hero8TsPipe}. */
@UnstableApi
public final class Hero8PreviewPlayer implements AutoCloseable {

    private static final String TAG = "Hero8PreviewPlayer";
    private static final String PIPE_URI = "hero8ts://preview";

    private final PlayerView playerView;
    private final ExoPlayer player;
    private final ProgressiveMediaSource.Factory mediaSourceFactory;
    private final Hero8TsPipe tsPipe = Hero8TsPipe.shared();

    public Hero8PreviewPlayer(@NonNull Context context, @NonNull PlayerView playerView) {
        this.playerView = playerView;

        Context appContext = context.getApplicationContext();

        DefaultLoadControl loadControl =
                new DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming(
                                300,   // minBufferMs
                                1_200, // maxBufferMs
                                100,   // bufferForPlaybackMs
                                150)   // bufferForPlaybackAfterRebufferMs
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

        DefaultExtractorsFactory extractorsFactory =
                new DefaultExtractorsFactory()
                        .setTsExtractorMode(TsExtractor.MODE_HLS)
                        .setTsExtractorTimestampSearchBytes(8_192);

        mediaSourceFactory =
                new ProgressiveMediaSource.Factory(
                        tsPipe.dataSourceFactory(),
                        extractorsFactory);

        player =
                new ExoPlayer.Builder(appContext)
                        .setLoadControl(loadControl)
                        .build();

        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        String detail = describeError(error);
                        Log.e(TAG, detail, error);
                        playerView.setCustomErrorMessage(detail);
                    }

                    @Override
                    public void onRenderedFirstFrame() {
                        playerView.setCustomErrorMessage(null);
                    }
                });

        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
    }

    /** Starts a fresh in-memory MPEG-TS session. Packets may arrive before Media3 reads them. */
    public void start() {
        player.stop();
        player.clearMediaItems();
        playerView.setCustomErrorMessage(null);
        tsPipe.reset();

        MediaItem mediaItem =
                new MediaItem.Builder()
                        .setUri(PIPE_URI)
                        .setMimeType(MimeTypes.VIDEO_MP2T)
                        .build();

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));
        player.prepare();
        player.play();
    }

    public void stop() {
        tsPipe.endStream();
        player.stop();
        player.clearMediaItems();
        playerView.setCustomErrorMessage(null);
    }

    @NonNull
    private static String describeError(@NonNull PlaybackException error) {
        Throwable deepest = error;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }

        String causeName = deepest.getClass().getSimpleName();
        String causeMessage = deepest.getMessage();
        if (causeMessage == null || causeMessage.isBlank()) {
            causeMessage = error.getMessage();
        }
        if (causeMessage == null || causeMessage.isBlank()) {
            causeMessage = "không có message";
        }
        causeMessage = causeMessage.replace('\n', ' ').replace('\r', ' ').trim();
        if (causeMessage.length() > 180) {
            causeMessage = causeMessage.substring(0, 180) + "…";
        }

        return "Media3: "
                + error.getErrorCodeName()
                + " | "
                + causeName
                + ": "
                + causeMessage;
    }

    @Override
    public void close() {
        tsPipe.endStream();
        playerView.setPlayer(null);
        player.release();
    }
}
