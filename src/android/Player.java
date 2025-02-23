/*
 The MIT License (MIT)

 Copyright (c) 2017 Nedim Cholich

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 */
package co.frontyard.cordova.plugin.exoplayer;

import static com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO;
import static com.google.android.exoplayer2.C.TRACK_TYPE_TEXT;
import static com.google.android.exoplayer2.C.WAKE_MODE_NETWORK;
import com.google.common.collect.ImmutableList;

import android.graphics.Color;
import android.util.Log;
import android.app.*;
import android.content.*;
import android.media.*;
import android.net.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.ContentFrameLayout;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.source.dash.*;
import com.google.android.exoplayer2.source.hls.*;
import com.google.android.exoplayer2.source.smoothstreaming.*;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;
import com.google.android.exoplayer2.Player.PositionInfo;
import java.lang.*;

import org.apache.cordova.*;
import org.json.*;

public class Player {
    public static final String TAG = "ExoPlayerPlugin";
    private final Activity activity;
    private final CallbackContext callbackContext;
    private final Configuration config;
    private Dialog dialog;
    private ExoPlayer exoPlayer;
    private StyledPlayerView exoView;
    private CordovaWebView webView;
    private int controllerVisibility;
    private boolean paused = false;
    private AudioManager audioManager;
    private ViewGroup parentLayout;
    private Tracks lastSeenTracks;
    public Player(Configuration config, Activity activity, CallbackContext callbackContext, CordovaWebView webView) {
        this.config = config;
        this.activity = activity;
        this.callbackContext = callbackContext;
        this.webView = webView;
        this.audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }

    private ExoPlayer.Listener playerEventListener = new ExoPlayer.Listener() {
        @Override
        public void onIsLoadingChanged(boolean isLoading) {
            JSONObject payload = Payload.loadingEvent(Player.this.exoPlayer, isLoading);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }

        @Override
        public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
            Log.i(TAG, "Playback parameters changed");
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if(lastSeenTracks != tracks) {
                lastSeenTracks = tracks;
                JSONObject payload = Payload.tracksChanged(Player.this.exoPlayer, tracks);
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            JSONObject payload = Payload.playerErrorEvent(Player.this.exoPlayer, error, null);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.ERROR, payload, true);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            JSONObject payload = Payload.isPlayingChanged(Player.this.exoPlayer);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (config.getShowBuffering()) {
                LayoutProvider.setBufferingVisibility(exoView, activity, playbackState == ExoPlayer.STATE_BUFFERING);
            }
            JSONObject payload = Payload.stateEvent(Player.this.exoPlayer, playbackState, Player.this.controllerVisibility == View.VISIBLE);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }

        @Override
        public void onPositionDiscontinuity(@NonNull PositionInfo oldPosition, @NonNull PositionInfo newPosition, int reason) {
            JSONObject payload = Payload.positionDiscontinuityEvent(Player.this.exoPlayer, reason);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }

        @Override
        public void onRepeatModeChanged(int newRepeatMode) {
            // Need to see if we want to send this to Cordova.
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            JSONObject payload = Payload.timelineChangedEvent(Player.this.exoPlayer, timeline);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }
    };

    private DialogInterface.OnDismissListener dismissListener = new DialogInterface.OnDismissListener() {
        @Override
        public void onDismiss(DialogInterface dialog) {
            Log.i(TAG, "Player dialog dismissed");

            if (exoPlayer != null) {
                exoPlayer.release();
            }
            exoPlayer = null;
            JSONObject payload = Payload.stopEvent(null);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }
    };

    private DialogInterface.OnKeyListener onKeyListener = new DialogInterface.OnKeyListener() {
        @Override
        public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
            String key = KeyEvent.keyCodeToString(event.getKeyCode());
            Log.i(TAG, "onKey listener " + keyCode + "/ '" + key + "'");

            // We need android to handle these key events
            if (key.equals("KEYCODE_VOLUME_UP") ||
                    key.equals("KEYCODE_VOLUME_DOWN") ||
                    key.equals("KEYCODE_VOLUME_MUTE")) {
                return false;
            }
            else {
                JSONObject payload = Payload.keyEvent(event);
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
                return true;
            }
        }
    };

    private View.OnTouchListener onTouchListener = new View.OnTouchListener() {
        int previousAction = -1;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int eventAction = event.getAction();
            if (previousAction != eventAction) {
                previousAction = eventAction;
                JSONObject payload = Payload.touchEvent(event);
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
            return true;
        }
    };

    private StyledPlayerView.ControllerVisibilityListener playbackControlVisibilityListener = new StyledPlayerView.ControllerVisibilityListener() {
        @Override
        public void onVisibilityChanged(int visibility) {
            Player.this.controllerVisibility = visibility;
        }
    };

    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                JSONObject payload = Payload.audioFocusEvent(Player.this.exoPlayer, "AUDIOFOCUS_LOSS_TRANSIENT");
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
            else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                JSONObject payload = Payload.audioFocusEvent(Player.this.exoPlayer, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
            else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                JSONObject payload = Payload.audioFocusEvent(Player.this.exoPlayer, "AUDIOFOCUS_GAIN");
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
            else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                JSONObject payload = Payload.audioFocusEvent(Player.this.exoPlayer, "AUDIOFOCUS_LOSS");
                new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
            }
        }
    };

    public void createPlayer() {
        Log.i(TAG, "Playing " + config.getUri());

        if (config.useInlineView()) {
            // Using a dialog doesn't work for us, as controls are drawn in HTML view (cordova ui)
            createPlayerInCordovaUI();

        } else if (!config.isAudioOnly()) {
            createDialog();
        }
        preparePlayer(config.getUri());

        if (config.useInlineView()) {
            setController(config.getController());
        }
    }

    public void createDialog() {
        dialog = new Dialog(this.activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setOnKeyListener(onKeyListener);
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View decorView = dialog.getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(dismissListener);

        FrameLayout mainLayout = LayoutProvider.getMainLayout(this.activity);
        exoView = LayoutProvider.getExoPlayerView(this.activity, config);
        exoView.setControllerVisibilityListener(playbackControlVisibilityListener);

        mainLayout.addView(exoView);
        dialog.setContentView(mainLayout);
        dialog.show();

        dialog.getWindow().setAttributes(LayoutProvider.getDialogLayoutParams(activity, config, dialog));
        exoView.requestFocus();
        exoView.setOnTouchListener(onTouchListener);
        LayoutProvider.setupController(exoView, activity, config.getController());
    }

    public void createPlayerInCordovaUI() {
        exoView = LayoutProvider.getExoPlayerView(this.activity, config);
        exoView.setControllerVisibilityListener(playbackControlVisibilityListener);

        exoView.setElevation(99);
        exoView.setVisibility(View.VISIBLE);
        exoView.setBackgroundColor(Color.BLACK);

        try {
            View webViewImpl = webView.getView();
            ViewParent webViewParent = webViewImpl.getParent();
            Log.d(TAG, "Have a " + (webViewImpl == null ? "empty" : "valid") + " parent View");
            if (webViewImpl != null) {
                Log.d(TAG, "parentView is a " + webViewImpl.getClass().getCanonicalName());
            }

            // Cordova webview is in a ContentFrameLayout (for plugin version 12 it is)
            parentLayout = (ViewGroup)webViewParent;
            parentLayout.addView(exoView);

            // Keep controls on top of player.
            webViewImpl.setElevation(101);
            Log.d(TAG, "parentView elevation 99");
            // webViewImpl.setBackgroundColor(Color.TRANSPARENT);
            // webViewImpl.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null);
        }
        catch (Exception e) {
            Log.e(TAG, "Problem adding exoplayer to cordova's webview containers: " + e.getMessage());
        }

        exoView.requestFocus();
        exoView.setOnTouchListener(onTouchListener);
    }

    public void setPlayerDimensions(JSONObject dimensions) {
        if(null != exoView){
            LayoutProvider.setExoPlayerViewLayout(activity, exoView, dimensions);
        }
    }

    public void setActiveTrack(JSONObject trackData) {
        if(null != exoPlayer){
           String typeStr=  trackData.optString("type");
            int trackIndex =  trackData.optInt("index", -1);
            int groupIndex =  trackData.optInt("group", -1);
            int type = switch (typeStr){
                   case "Text" -> TRACK_TYPE_TEXT;
                   case "Audio" -> TRACK_TYPE_AUDIO;
                   default -> -1;
               };
           if(type >= 0 && trackIndex >= 0 && groupIndex >= 0 && lastSeenTracks != null)
           {
               ImmutableList<Tracks. Group> groups = lastSeenTracks.getGroups();
               if(groupIndex < groups.size()) {
                    Tracks.Group trackGroup = groups.get(groupIndex);
                    //    for (int i = 0; i < groups.size(); i++) {
                    //        Tracks.Group  group= groups.get(groupIndex);
                    //        if (group.getType() == type) {
                    //            trackGroup = group;
                    //        }
                    //    }
                    if(null != trackGroup) {
                        exoPlayer.setTrackSelectionParameters(
                                exoPlayer
                                        .getTrackSelectionParameters()
                                        .buildUpon()
                                        .setOverrideForType(
                                                new TrackSelectionOverride(
                                                        trackGroup.getMediaTrackGroup(), /* trackIndex= */ trackIndex))
                                        .build());
                    }
               }
           }
        }
    }

    private int setupAudio() {
        activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        return audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void preparePlayer(Uri uri) {
        int audioFocusResult = setupAudio();
        String audioFocusString = audioFocusResult == AudioManager.AUDIOFOCUS_REQUEST_FAILED ?
                "AUDIOFOCUS_REQUEST_FAILED" :
                "AUDIOFOCUS_REQUEST_GRANTED";
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(this.activity).build();
        //TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveVideoTrackSelection.Factory(bandwidthMeter);

        exoPlayer = new ExoPlayer.Builder(this.activity).setWakeMode(WAKE_MODE_NETWORK).build();
        exoPlayer.addListener(playerEventListener);
        if (null != exoView) {
            exoView.setPlayer(new ForwardingPlayer(exoPlayer) {
                @Override
                public long getSeekForwardIncrement() {
                    Log.d(TAG, "ForwardingPlayer::getSeekForwardIncrement: " + config.getForwardTimeMs());
                    return config.getForwardTimeMs();
                }

                @Override
                public long getSeekBackIncrement() {
                    Log.d(TAG, "ForwardingPlayer::getSeekBackIncrement: " + config.getRewindTimeMs());
                    return config.getRewindTimeMs();
                }
            });
        }

        MediaSource mediaSource = getMediaSource(uri, bandwidthMeter);
        if (mediaSource != null) {
            long startTimeMS = config.getSeekTo();
            boolean autoPlay = config.autoPlay();
            if (startTimeMS > 0) {
                exoPlayer.setMediaSource(mediaSource, startTimeMS);
            } else {
                exoPlayer.setMediaSource(mediaSource);
            }
            exoPlayer.prepare();

            exoPlayer.setPlayWhenReady(autoPlay);
            paused = !autoPlay;

            JSONObject payload = Payload.startEvent(exoPlayer, audioFocusString);
            new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.OK, payload, true);
        }
        else {
            sendError("Failed to construct mediaSource for " + uri);
        }
    }

    private MediaSource getMediaSource(Uri uri, DefaultBandwidthMeter bandwidthMeter) {
        String userAgent = Util.getUserAgent(this.activity, config.getUserAgent());
        int connectTimeout = config.getConnectTimeout();
        int readTimeout = config.getReadTimeout();

        HttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setTransferListener(bandwidthMeter)
                .setConnectTimeoutMs(connectTimeout)
                .setReadTimeoutMs(readTimeout)
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this.activity, httpDataSourceFactory)
                .setTransferListener(bandwidthMeter);
        MediaSource mediaSource;
        int type = Util.inferContentType(uri);
        mediaSource = switch (type) {
            case C.CONTENT_TYPE_DASH -> new DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(new MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .build());
            case C.CONTENT_TYPE_HLS -> new HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri)
                    );
            case C.CONTENT_TYPE_SS -> new SsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_RTSP -> new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
            default -> new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
        };

        String subtitleUrl = config.getSubtitleUrl();
        if (null != subtitleUrl) {
            Uri subtitleUri = Uri.parse(subtitleUrl);
            String subtitleType = inferSubtitleType(subtitleUri);
            Log.i(TAG, "Subtitle present: " + subtitleUri + ", type=" + subtitleType);
            MediaSource subtitleSource = new SingleSampleMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(
                            new MediaItem.SubtitleConfiguration.Builder(uri)
                                    .setMimeType(subtitleType)
                                    .setLanguage("en")
                                    .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
                                    .build(),
                            C.TIME_UNSET);
            return new MergingMediaSource(mediaSource, subtitleSource);
        }
        else {
            return mediaSource;
        }
    }

    private static String inferSubtitleType(Uri uri) {
        String fileName = uri.getPath().toLowerCase();

        if (fileName.endsWith(".vtt")) {
            return MimeTypes.TEXT_VTT;
        }
        else {
            // Assume it's srt.
            return MimeTypes.APPLICATION_SUBRIP;
        }
    }

    public void close() {
        Log.i(TAG, "closing stream");
        audioManager.abandonAudioFocus(audioFocusChangeListener);
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        if (this.dialog != null) {
            dialog.dismiss();
        }

        if (parentLayout != null && exoView != null) {
            parentLayout.removeView(exoView);
        }
    }

    public void setStream(Uri uri, JSONObject controller) {
        if (null != uri && null != exoPlayer) {
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(null).build();
            MediaSource mediaSource = getMediaSource(uri, bandwidthMeter);
            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();
            play();
        }
        setController(controller);
    }

    public void playPause() {
        if (this.paused) {
            play();
        }
        else {
            pause();
        }
    }

    public void pause() {

        if (null != exoPlayer && !paused) {
            paused = true;
            exoPlayer.setPlayWhenReady(false);
        }
    }

    public void play() {
        if (null != exoPlayer && paused) {
            paused = false;
            exoPlayer.setPlayWhenReady(true);
        }
    }

    public void stop() {
        Log.i(TAG, "STOP" +  ( (null == exoPlayer) ? " exoPlayer not yet initialized" : ""));
        if (null != exoPlayer) {
            paused = false;
            exoPlayer.stop();
        }
    }

    private long normalizeOffset(long newTime) {
        long duration = exoPlayer.getDuration();
        if (duration == C.TIME_UNSET) return newTime;

        return Math.min(Math.max(0, newTime), duration);
    }

    public JSONObject seekTo(long timeMillis) {
        long newTime = normalizeOffset(timeMillis);
        Log.i(TAG, "SEEK (to) " +  timeMillis  + " / " + newTime + " (normalized)");

        exoPlayer.seekTo(newTime);
        return Payload.seekEvent(this.exoPlayer, newTime);
    }

    public JSONObject seekBy(long timeMillis) {
        long newTime = normalizeOffset(exoPlayer.getCurrentPosition() + timeMillis);
        Log.i(TAG, "SEEK (by)" +  timeMillis  + " / " + newTime + " (normalized)");

        exoPlayer.seekTo(newTime);
        return Payload.seekEvent(this.exoPlayer, newTime);
    }

    public JSONObject getPlayerState() {
        return Payload.stateEvent(exoPlayer,
                null != exoPlayer ? exoPlayer.getPlaybackState() : com.google.android.exoplayer2.Player.STATE_ENDED,
                Player.this.controllerVisibility == View.VISIBLE);
    }

    public void showController() {
        if (null != exoView) {
            exoView.showController();
        }
    }

    public void hideController() {
        if (null != exoView) {
            exoView.hideController();
        }
    }

    public void setController(JSONObject controller) {
        if (null != exoView) {
            LayoutProvider.setupController(exoView, activity, controller);
        }
    }

    private void sendError(String msg) {
        Log.e(TAG, msg);
        JSONObject payload = Payload.playerErrorEvent(Player.this.exoPlayer, null, msg);
        new CallbackResponse(Player.this.callbackContext).send(PluginResult.Status.ERROR, payload, true);
    }

    public void setZIndex(int index) {
        Log.i(TAG, "setZIndex: " + index);
        if(null != exoView)
        {
            exoView.setElevation(index);
            exoView.invalidate();
            this.parentLayout.invalidate();
        }
    }
}
