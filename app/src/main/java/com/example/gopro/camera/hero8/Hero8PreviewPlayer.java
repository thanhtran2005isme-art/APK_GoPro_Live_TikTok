package com.example.gopro.camera.hero8;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Small Media3 wrapper that renders the local MPEG-TS relay produced by
 * {@link Hero8UdpPreviewProbe}.
 */
public final class Hero8PreviewPlayer implements AutoCloseable {

    public static final int LOCAL_RELAY_PORT = 8555;
    private static final String LOCAL_RELAY_URI = "udp://127.0.0.1:" + LOCAL_RELAY_PORT;

    private final PlayerView playerView;
    private final ExoPlayer player;

    public Hero8PreviewPlayer(@NonNull Context context, @NonNull PlayerView playerView) {
        this.playerView = playerView;
        player = new ExoPlayer.Builder(context.getApplicationContext()).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }

    public void start() {
        player.stop();
        player.clearMediaItems();
        player.setMediaItem(MediaItem.fromUri(LOCAL_RELAY_URI));
        player.prepare();
        player.play();
    }

    public void stop() {
        player.stop();
        player.clearMediaItems();
    }

    @Override
    public void close() {
        playerView.setPlayer(null);
        player.release();
    }
}
