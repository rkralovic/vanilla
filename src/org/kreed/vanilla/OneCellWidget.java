/*
 * Copyright (C) 2010 Christopher Eby <kreed@kreed.org>
 *
 * This file is part of Vanilla Music Player.
 *
 * Vanilla Music Player is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Vanilla Music Player is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.kreed.vanilla;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

public class OneCellWidget extends AppWidgetProvider {
	@Override
	public void onUpdate(Context context, AppWidgetManager manager, int[] ids)
	{
		PlaybackServiceState state = new PlaybackServiceState();
		Song song = null;
		if (state.load(context)) {
			song = new Song(state.savedIds[state.savedIndex]);
			if (!song.populate())
				song = null;
		}

		RemoteViews views = createViews(context, song, false);
		manager.updateAppWidget(ids, views);
	}

	@Override
	public void onReceive(Context context, Intent intent)
	{
		if (PlaybackService.EVENT_CHANGED.equals(intent.getAction())) {
			Song song = intent.getParcelableExtra("song");
			boolean playing = intent.getIntExtra("newState", 0) == PlaybackService.STATE_PLAYING;

			ComponentName widget = new ComponentName(context, OneCellWidget.class);
			RemoteViews views = createViews(context, song, playing);

			AppWidgetManager.getInstance(context).updateAppWidget(widget, views);
		} else {
			super.onReceive(context, intent);
		}
	}

	public static RemoteViews createViews(Context context, Song song, boolean playing)
	{
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.one_cell_widget);

		int toggle;

		// Unfortunately we have to have two views since we can not call
		// setBackgroundResource from RemoteViews
		if (playing) {
			toggle = R.id.pause;
			views.setViewVisibility(R.id.play, View.GONE);
			views.setViewVisibility(R.id.pause, View.VISIBLE);
		} else {
			toggle = R.id.play;
			views.setViewVisibility(R.id.pause, View.GONE);
			views.setViewVisibility(R.id.play, View.VISIBLE);
		}

		views.setOnClickPendingIntent(toggle, PendingIntent.getBroadcast(context, 0, new Intent(PlaybackService.TOGGLE_PLAYBACK), 0));
		views.setOnClickPendingIntent(R.id.next, PendingIntent.getBroadcast(context, 0, new Intent(PlaybackService.NEXT_SONG), 0));

		if (song == null) {
			views.setImageViewResource(R.id.cover_view, R.drawable.icon);
		} else {
			int size = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72, context.getResources().getDisplayMetrics());
			views.setImageViewBitmap(R.id.cover_view, CoverView.createMiniBitmap(song, size, size));
		}

		return views;
	}
}