package org.llm4people.hermes.os;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

/** Quick Settings tile "Demander à Hermes" launching the unlocked composer or profile gate. */
public final class HermesTileService extends TileService {
    private static final int REQUEST_TILE = 4120;

    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.ask_hermes));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(R.string.tile_subtitle));
        }
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        Diagnostics.event("os", "qs_tile_click");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pending = OsEntryIntent.pendingIntent(this, OsEntryIntent.Target.ASK, REQUEST_TILE);
                startActivityAndCollapse(pending);
            } else {
                launchLegacy(OsEntryIntent.intent(this, OsEntryIntent.Target.ASK));
            }
        } catch (RuntimeException error) {
            Diagnostics.warning("os", "qs_tile_launch_failed");
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void launchLegacy(Intent launch) {
        startActivityAndCollapse(launch);
    }
}
