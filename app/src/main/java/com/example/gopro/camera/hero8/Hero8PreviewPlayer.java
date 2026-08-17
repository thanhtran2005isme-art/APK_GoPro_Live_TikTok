package com.example.gopro.camera.hero8;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.PlayerView;

/**
 * Media3 renderer for the clean local MPEG-TS relay produced by {@link Hero8UdpPreviewProbe}.
 *
 * <p>The HERO8 viewfinder feed is an endless, low-latency MPEG-TS stream rather than a normal
 * seekable media file. Media3's generic defaults are intentionally conservative and can buffer far
 * more than a live camera preview needs. This player therefore pins the MPEG-TS extractor and uses
 * a small live buffer.</p>
 */
@UnstableApi
public final class Hero8PreviewPlayer implements AutoCloseable {

    public static final int LOCAL_RELAY_PORT = 8555;
    private static final String LOCAL_RELAY_URI = "udp://127.0.0.1:" + LOCAL_RELAY_PORT;

    private final PlayerView playerView;
    private final ExoPlayer player;
    private final ProgressiveMediaSource.Factory mediaSourceFactory;

    public Hero8PreviewPlayer(@NonNull Context context, @NonNull PlayerView playerView) {
        this.playerView = playerView;

        Context appContext = context.getApplicationContext();

        DefaultLoadControl loadControl =
                new DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming(
                                500,  // minBufferMs
                                1_500, // maxBufferMs
                                100,  // bufferForPlaybackMs
                                250)  // bufferForPlaybackAfterRebufferMs
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

        DefaultExtractorsFactory extractorsFactory =
                new DefaultExtractorsFactory()
                        // HERO8 is a single-program live TS feed. HLS mode is useful here because
                        // it ignores continuity-counter gaps caused by normal UDP packet loss.
                        .setTsExtractorMode(TsExtractor.MODE_HLS)
                        // Do not spend a large amount of startup data trying to infer duration for
                        // an endless live stream.
                        .setTsExtractorTimestampSearchBytes(8_192);

        mediaSourceFactory =
                new ProgressiveMediaSource.Factory(
                        new DefaultDataSource.Factory(appContext), extractorsFactory);

        player =
                new ExoPlayer.Builder(appContext)
                        .setLoadControl(loadControl)
                        .build();

        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        String detail = error.getMessage();
                        if (detail == null || detail.isBlank()) {
                            detail = error.getErrorCodeName();
                        }
                        playerView.setCustomErrorMessage(
                                "Media3 không decode được preview:\n"
                                        + error.getErrorCodeName()
                                        + "\n"
                                        + detail);
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

    public void start() {
        player.stop();
        player.clearMediaItems();
        playerView.setCustomErrorMessage(null);

        MediaItem mediaItem =
                new MediaItem.Builder()
                        .setUri(LOCAL_RELAY_URI)
                        .setMimeType(MimeTypes.VIDEO_MP2T)
                        .build();

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));
        player.prepare();
        player.play();
    }

    public void stop() {
        player.stop();
        player.clearMediaItems();
        playerView.setCustomErrorMessage(null);
    }

    @Override
    public void close() {
        playerView.setPlayer(null);
        player.release();
    }
}
