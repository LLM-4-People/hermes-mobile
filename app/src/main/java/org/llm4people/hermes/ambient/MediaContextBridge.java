package org.llm4people.hermes.ambient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Now Playing reader and user-confirmed playback controller using {@link MediaSessionManager}
 * via {@link HermesNotificationListener} (zero additional Android permissions).
 */
public final class MediaContextBridge {
    public enum PlaybackKind {
        PLAYING,
        PAUSED,
        BUFFERING,
        STOPPED,
        UNKNOWN
    }

    public enum ControlAction {
        PLAY,
        PAUSE,
        NEXT,
        PREVIOUS
    }

    public record NowPlaying(
            String packageName,
            String appLabel,
            String title,
            String artist,
            String album,
            PlaybackKind playbackKind,
            long positionMs,
            long durationMs) {
        public NowPlaying {
            packageName = packageName == null ? "" : packageName.trim();
            appLabel = NotificationPrivacyGuard.sanitizeText(appLabel, NotificationPrivacyGuard.MAX_LABEL_CHARS);
            title = NotificationPrivacyGuard.sanitizeText(title, NotificationPrivacyGuard.MAX_TITLE_CHARS);
            artist = NotificationPrivacyGuard.sanitizeText(artist, NotificationPrivacyGuard.MAX_SENDER_CHARS);
            album = NotificationPrivacyGuard.sanitizeText(album, NotificationPrivacyGuard.MAX_TITLE_CHARS);
            playbackKind = playbackKind == null ? PlaybackKind.UNKNOWN : playbackKind;
            positionMs = Math.max(0L, positionMs);
            durationMs = Math.max(0L, durationMs);
        }

        public String displayTrack() {
            if (!title.isEmpty() && !artist.isEmpty()) return title + " — " + artist;
            if (!title.isEmpty()) return title;
            if (!artist.isEmpty()) return artist;
            return !appLabel.isEmpty() ? appLabel : packageName;
        }

        public String toSanitizedJson() {
            return "{"
                    + "\"package\":" + AmbientJson.quote(packageName)
                    + ",\"app\":" + AmbientJson.quote(appLabel)
                    + ",\"title\":" + AmbientJson.quote(title)
                    + ",\"artist\":" + AmbientJson.quote(artist)
                    + ",\"album\":" + AmbientJson.quote(album)
                    + ",\"state\":" + AmbientJson.quote(playbackKind.name().toLowerCase(java.util.Locale.ROOT))
                    + ",\"position_ms\":" + positionMs
                    + ",\"duration_ms\":" + durationMs
                    + "}";
        }
    }

    private MediaContextBridge() {}

    /** Read active media sessions only when the profile opted into media context. */
    public static List<NowPlaying> readActiveSessions(Context context, boolean optInEnabled) {
        if (!optInEnabled || context == null || !HermesNotificationListener.isListenerEnabled(context)) {
            return Collections.emptyList();
        }
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return Collections.emptyList();
        try {
            ComponentName listener = HermesNotificationListener.componentName(context);
            List<MediaController> controllers = manager.getActiveSessions(listener);
            if (controllers == null || controllers.isEmpty()) return Collections.emptyList();
            List<NowPlaying> result = new ArrayList<>(controllers.size());
            for (MediaController controller : controllers) {
                NowPlaying item = toNowPlaying(context, controller);
                if (item != null) result.add(item);
            }
            return Collections.unmodifiableList(result);
        } catch (SecurityException ex) {
            Diagnostics.warning("ambient", "media_access_denied", ex);
            return Collections.emptyList();
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "media_read_failed", ex);
            return Collections.emptyList();
        }
    }

    /** Pick the primary active session, preferring an actively playing track. */
    public static NowPlaying selectPrimarySession(List<NowPlaying> sessions) {
        if (sessions == null || sessions.isEmpty()) return null;
        for (NowPlaying item : sessions) {
            if (item.playbackKind() == PlaybackKind.PLAYING || item.playbackKind() == PlaybackKind.BUFFERING) {
                return item;
            }
        }
        return sessions.get(0);
    }

    /** Execute a transport command ONLY after explicit user confirmation. */
    public static boolean dispatchConfirmedAction(
            Context context,
            boolean optInEnabled,
            String targetPackage,
            ControlAction action,
            boolean userConfirmed) {
        if (!userConfirmed) {
            Diagnostics.warning("ambient", "media_control_rejected", "unconfirmed");
            throw new IllegalStateException("Media control requires explicit user confirmation");
        }
        if (!optInEnabled || context == null || action == null || !HermesNotificationListener.isListenerEnabled(context)) {
            return false;
        }
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return false;
        try {
            List<MediaController> controllers = manager.getActiveSessions(
                    HermesNotificationListener.componentName(context));
            if (controllers == null || controllers.isEmpty()) return false;
            MediaController target = findController(controllers, targetPackage);
            if (target == null) return false;
            MediaController.TransportControls controls = target.getTransportControls();
            switch (action) {
                case PLAY -> controls.play();
                case PAUSE -> controls.pause();
                case NEXT -> controls.skipToNext();
                case PREVIOUS -> controls.skipToPrevious();
            }
            Diagnostics.event("ambient", "media_control", action.name().toLowerCase(java.util.Locale.ROOT));
            return true;
        } catch (SecurityException ex) {
            Diagnostics.warning("ambient", "media_control_denied", ex);
            return false;
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "media_control_failed", ex);
            return false;
        }
    }

    /** Show an explicit FLAG_SECURE confirmation dialog before sending a playback control. */
    public static AlertDialog showControlConfirmation(
            Activity activity,
            boolean optInEnabled,
            NowPlaying track,
            ControlAction action,
            Consumer<Boolean> onResult) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(action, "action");
        String actionLabel = actionLabel(activity, action);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.ambient_media_confirm_title)
                .setMessage(activity.getString(R.string.ambient_media_confirm_body, actionLabel, track.displayTrack()))
                .setNegativeButton(R.string.ambient_reply_cancel, (d, w) -> {
                    if (onResult != null) onResult.accept(false);
                })
                .setPositiveButton(R.string.ambient_media_confirm_ok, (d, w) -> {
                    boolean ok = dispatchConfirmedAction(activity, optInEnabled, track.packageName(), action, true);
                    if (onResult != null) onResult.accept(ok);
                });
        return Ui.secureDialog(builder);
    }

    public static PlaybackKind mapPlaybackState(int androidPlaybackState) {
        return switch (androidPlaybackState) {
            case PlaybackState.STATE_PLAYING -> PlaybackKind.PLAYING;
            case PlaybackState.STATE_PAUSED -> PlaybackKind.PAUSED;
            case PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlaybackKind.BUFFERING;
            case PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> PlaybackKind.STOPPED;
            default -> PlaybackKind.UNKNOWN;
        };
    }

    private static NowPlaying toNowPlaying(Context context, MediaController controller) {
        if (controller == null) return null;
        String pkg = controller.getPackageName();
        MediaMetadata meta = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        String title = meta == null ? "" : safeMeta(meta, MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta == null ? "" : safeMeta(meta, MediaMetadata.METADATA_KEY_ARTIST);
        String album = meta == null ? "" : safeMeta(meta, MediaMetadata.METADATA_KEY_ALBUM);
        long duration = meta == null ? 0L : Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = state == null ? 0L : Math.max(0L, state.getPosition());
        PlaybackKind kind = state == null ? PlaybackKind.UNKNOWN : mapPlaybackState(state.getState());
        return new NowPlaying(pkg, resolveLabel(context, pkg), title, artist, album, kind, position, duration);
    }

    /**
     * Select the session matching an explicit {@code targetPackage} (fail closed: returns {@code null}
     * if {@code targetPackage} is specified but not present — never falls through to another app).
     * When {@code targetPackage} is null/empty, delegates to {@link #selectPrimarySession(List)}.
     */
    public static NowPlaying selectTargetSession(List<NowPlaying> sessions, String targetPackage) {
        if (sessions == null || sessions.isEmpty()) return null;
        if (targetPackage != null && !targetPackage.trim().isEmpty()) {
            String wanted = targetPackage.trim();
            for (NowPlaying item : sessions) {
                if (wanted.equals(item.packageName())) return item;
            }
            return null;
        }
        return selectPrimarySession(sessions);
    }

    private static MediaController findController(List<MediaController> controllers, String targetPackage) {
        if (targetPackage != null && !targetPackage.trim().isEmpty()) {
            String wanted = targetPackage.trim();
            for (MediaController c : controllers) {
                if (wanted.equals(c.getPackageName())) return c;
            }
            // Fail closed: never redirect a confirmed control action to a different media app.
            Diagnostics.warning("ambient", "media_target_missing");
            return null;
        }
        for (MediaController c : controllers) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return controllers.get(0);
    }

    private static String safeMeta(MediaMetadata meta, String key) {
        CharSequence seq = meta.getText(key);
        return seq == null ? "" : seq.toString();
    }

    private static String resolveLabel(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return pkg;
        }
    }

    private static String actionLabel(Context context, ControlAction action) {
        return switch (action) {
            case PLAY -> context.getString(R.string.ambient_media_play);
            case PAUSE -> context.getString(R.string.ambient_media_pause);
            case NEXT -> context.getString(R.string.ambient_media_next);
            case PREVIOUS -> context.getString(R.string.ambient_media_previous);
        };
    }
}
