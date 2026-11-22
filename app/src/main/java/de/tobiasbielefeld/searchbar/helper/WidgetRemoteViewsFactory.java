/*
 * Copyright (C) 2026  Tobias Bielefeld
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * If you want to contact me, send me an e-mail at tobias.bielefeld@gmail.com
 */

package de.tobiasbielefeld.searchbar.helper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import de.tobiasbielefeld.searchbar.ui.MainActivity;

/**
 * Shared click targets for the three home-screen widgets. The text area opens the
 * app; the microphone opens it with the voice extra. The two PendingIntents use
 * different request codes because Intent.filterEquals ignores extras.
 */
public final class WidgetRemoteViewsFactory {

    public static final String EXTRA_VOICE = "de.tobiasbielefeld.searchbar.extra.VOICE";

    private static final int REQUEST_OPEN = 0;
    private static final int REQUEST_VOICE = 1;

    private WidgetRemoteViewsFactory() {
    }

    public static void update(Context context, AppWidgetManager manager, int[] appWidgetIds, int layoutId, int textId, int voiceId) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), layoutId);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Intent openIntent = new Intent(context, MainActivity.class);
        remoteViews.setOnClickPendingIntent(textId,
                PendingIntent.getActivity(context, REQUEST_OPEN, openIntent, flags));

        Intent voiceIntent = new Intent(context, MainActivity.class);
        voiceIntent.putExtra(EXTRA_VOICE, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            voiceIntent.setIdentifier("voice");
        }
        remoteViews.setOnClickPendingIntent(voiceId,
                PendingIntent.getActivity(context, REQUEST_VOICE, voiceIntent, flags));

        manager.updateAppWidget(appWidgetIds, remoteViews);
    }
}
