/*
 * Copyright (C) 2017  Tobias Bielefeld
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

package de.tobiasbielefeld.searchbar.classes;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

import de.tobiasbielefeld.searchbar.R;
import de.tobiasbielefeld.searchbar.helper.WidgetRemoteViewsFactory;

/**
 * Starts the app when pressing the widget
 *
 * Created with the help of: http://stackoverflow.com/a/31629936/7016229
 */

public class CustomWidgetProvider2 extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        WidgetRemoteViewsFactory.update(context, appWidgetManager, appWidgetIds,
                R.layout.widget_layout2, R.id.widget_text, R.id.widget_voice);
    }
}