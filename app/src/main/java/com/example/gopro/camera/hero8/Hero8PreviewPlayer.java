package com.example.gopro.camera.hero8;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.UdpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.PlayerView;

/**
 * Media3 renderer for the clean local MPEG-TS relay produced by {@link Hero8UdpPreviewProbe}.
 *
 * <p>The local UDP source is prepared as soon as this class is created, before the camera preview
 * is started. This is intentional: HERO8 can send PAT/PMT/SPS immediately after gpStream starts,
 * and a late UDP bind can miss those bootstrap packets and leave the TS extractor unable to build
 * tracks even though later video packets keep arriving.</p>
 */
@UnstableApi
public final class Hero8PreviewPlayer implements AutoCloseable {

    private static final String TAG = "Hero8PreviewPlayer";

    public static final int LOCAL_RELAY_PORT = 8555;
    private static final String LOCAL_RELAY_URI = "udp://127.0.0.1:" + LOCAL_RELAY_PORT;
    private static final int MAX_UDP_PACKET_SIZE = 2_048;

    private final PlayerView playerView;
    private final ExoPlayer player;
    private final ProgressiveMediaSource.Factory mediaSourceFactory;

    private boolean preparedWaitingForStream;

    public Hero8PreviewPlayer(@NonNull Context context, @NonNull PlayerView playerView) {
        this.playerView = playerView;

        Context appContext = context.getApplicationContext();

        DefaultLoadControl loadControl =
                new DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming(
                                500,   // minBufferMs
                                1_500, // maxBufferMs
                                100,   // bufferForPlaybackMs
                                250)   // bufferForPlaybackAfterRebufferMs
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

        DefaultExtractorsFactory extractorsFactory =
                new DefaultExtractorsFactory()
                        .setTsExtractorMode(TsExtractor.MODE_HLS)
                        .setTsExtractorTimestampSearchBytes(8_192);

        // Use UdpDataSource directly rather than DefaultDataSource. Timeout=0 means infinite, so
        // Media3 may safely bind localhost:8555 before the user starts the HERO8 stream and wait
        // there without timing out while pairing/Wi-Fi setup is still happening.
        DataSource.Factory udpDataSourceFactory =
                () -> new UdpDataSource(MAX_UDP_PACKET_SIZE, 0);

        mediaSourceFactory =
                new ProgressiveMediaSource.Factory(udpDataSourceFactory, extractorsFactory);

        player =
                new ExoPlayer.Builder(appContext)
                        .setLoadControl(loadControl)
                        .build();

        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        preparedWaitingForStream = false;
                        String detail = describeError(error);
                        Log.e(TAG, detail, error);
                        // Keep the on-screen error compact enough that PlayerView does not clip the
                        // useful error code behind its internal error-message layout.
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

        // Bind localhost:8555 now. MainActivity may start HERO8 much later; the socket remains
        // blocked waiting for the first relay packet without an 8-second UDP timeout.
        prepareWaitingForStream();
    }

    /** Starts rendering without tearing down the already-bound UDP source. */
    public void start() {
        playerView.setCustomErrorMessage(null);
        if (!preparedWaitingForStream || player.getMediaItemCount() == 0) {
            prepareWaitingForStream();
        }
        player.play();
    }

    /**
     * Resets the extractor and immediately re-binds UDP so the next HERO8 start cannot outrun the
     * player. The source stays paused/buffering until {@link #start()} is called.
     */
    public void stop() {
        player.stop();
        player.clearMediaItems();
        playerView.setCustomErrorMessage(null);
        preparedWaitingForStream = false;
        prepareWaitingForStream();
    }

    private void prepareWaitingForStream() {
        player.stop();
        player.clearMediaItems();

        MediaItem mediaItem =
                new MediaItem.Builder()
                        .setUri(LOCAL_RELAY_URI)
                        .setMimeType(MimeTypes.VIDEO_MP2T)
                        .build();

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));
        player.setPlayWhenReady(false);
        player.prepare();
        preparedWaitingForStream = true;
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
        if (causeMessage.length() > 150) {
            causeMessage = causeMessage.substring(0, 150) + "…";
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
        playerView.setPlayer(null);
        player.release();
    }
}
