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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

public final class PlaybackService extends Service implements Handler.Callback, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, SharedPreferences.OnSharedPreferenceChangeListener, SongTimeline.Callback {	
	private static final int NOTIFICATION_ID = 2;

	/**
	 * Action for startService: toggle playback on/off.
	 */
	public static final String ACTION_TOGGLE_PLAYBACK = "org.kreed.vanilla.action.TOGGLE_PLAYBACK";
	/**
	 * Action for startService: toggle playback on/off.
	 *
	 * Unlike {@link PlaybackService#ACTION_TOGGLE_PLAYBACK}, the toggle does
	 * not occur immediately. Instead, it is delayed so that if two of these
	 * actions are received within 400 ms, the playback activity is opened
	 * instead.
	 */
	public static final String ACTION_TOGGLE_PLAYBACK_DELAYED = "org.kreed.vanilla.action.TOGGLE_PLAYBACK_DELAYED";
	/**
	 * Action for startService: advance to the next song.
	 */
	public static final String ACTION_NEXT_SONG = "org.kreed.vanilla.action.NEXT_SONG";
	/**
	 * Action for startService: advance to the next song.
	 *
	 * Unlike {@link PlaybackService#ACTION_NEXT_SONG}, the toggle does
	 * not occur immediately. Instead, it is delayed so that if two of these
	 * actions are received within 400 ms, the playback activity is opened
	 * instead.
	 */
	public static final String ACTION_NEXT_SONG_DELAYED = "org.kreed.vanilla.action.NEXT_SONG_DELAYED";
	/**
	 * Action for startService: advance to the next song.
	 *
	 * Like ACTION_NEXT_SONG, but starts playing automatically if paused
	 * when this is called.
	 */
	public static final String ACTION_NEXT_SONG_AUTOPLAY = "org.kreed.vanilla.action.NEXT_SONG_AUTOPLAY";
	/**
	 * Action for startService: go back to the previous song.
	 */
	public static final String ACTION_PREVIOUS_SONG = "org.kreed.vanilla.action.PREVIOUS_SONG";
	/**
	 * Action for startService: go back to the previous song.
	 *
	 * Like ACTION_PREVIOUS_SONG, but starts playing automatically if paused
	 * when this is called.
	 */
	public static final String ACTION_PREVIOUS_SONG_AUTOPLAY = "org.kreed.vanilla.action.PREVIOUS_SONG_AUTOPLAY";
	/**
	 * Intent action that may be invoked through startService.
	 *
	 * Given a song or group of songs, play the first and enqueues the rest after
	 * it.
	 *
	 * If FLAG_SHUFFLE is enabled, songs will be added to the song timeline in
	 * random order, otherwise, songs will be ordered by album name and then
	 * track number.
	 *
	 * Requires two extras: "type", which can be 1, 2, or 3, indicating artist,
	 * album, or song respectively, and "id", which is the MediaStore id for the
	 * song, album, or artist.
	 */
	public static final String ACTION_PLAY_ITEMS = "org.kreed.vanilla.action.PLAY_ITEMS";
	/**
	 * Intent action that may be invoked through startService.
	 *
	 * Enqueues a song or group of songs.
	 *
	 * The first song from the group will be placed in the timeline either
	 * after the last enqueued song or after the playing song if the queue is
	 * empty. If FLAG_SHUFFLE is enabled, songs will be added to the song
	 * timeline in random order, otherwise, songs will be ordered by album name
	 * and then track number.
	 *
	 * Requires two extras: "type", which can be 1, 2, or 3, indicating artist,
	 * album, or song respectively, and "id", which is the MediaStore id for the
	 * song, album, or artist.
	 */
	public static final String ACTION_ENQUEUE_ITEMS = "org.kreed.vanilla.action.ENQUEUE_ITEMS";
	/**
	 * Reset the position at which songs are enqueued, that is, new songs will
	 * be placed directly after the playing song after this action is invoked.
	 */
	public static final String ACTION_FINISH_ENQUEUEING = "org.kreed.vanilla.action.FINISH_ENQUEUEING";

	public static final String EVENT_REPLACE_SONG = "org.kreed.vanilla.event.REPLACE_SONG";
	public static final String EVENT_CHANGED = "org.kreed.vanilla.event.CHANGED";
	public static final String EVENT_INITIALIZED = "org.kreed.vanilla.event.INITIALIZED";

	public static final int FLAG_NO_MEDIA = 0x2;
	public static final int FLAG_PLAYING = 0x1;
	public static final int FLAG_SHUFFLE = 0x4;
	public static final int FLAG_REPEAT = 0x8;
	public static final int ALL_FLAGS = FLAG_NO_MEDIA + FLAG_PLAYING + FLAG_SHUFFLE + FLAG_REPEAT;
	/**
	 * The flags that are (usually) only toggled by user action.
	 */
	public static final int USER_MASK = FLAG_PLAYING + FLAG_SHUFFLE + FLAG_REPEAT;

	public static final int NEVER = 0;
	public static final int WHEN_PLAYING = 1;
	public static final int ALWAYS = 2;

	boolean mHeadsetPause;
	boolean mHeadsetOnly;
	private boolean mScrobble;
	private int mNotificationMode;
	/**
	 * The time to wait before considering the player idle.
	 */
	private int mIdleTimeout;

	private Looper mLooper;
	private Handler mHandler;
	MediaPlayer mMediaPlayer;
	private boolean mMediaPlayerInitialized;
	private PowerManager.WakeLock mWakeLock;
	private Notification mNotification;
	private SharedPreferences mSettings;
	private AudioManager mAudioManager;
	private NotificationManager mNotificationManager;

	SongTimeline mTimeline;
	int mState = 0x80;
	Object mStateLock = new Object();
	boolean mPlayingBeforeCall;
	private int mPendingSeek;
	private Song mLastSongBroadcast;
	boolean mPlugged;
	private ContentObserver mMediaObserver;
	public Receiver mReceiver;
	public InCallListener mCallListener;
	private boolean mLoaded;
	/**
	 * The volume set by the user in the preferences.
	 */
	private float mUserVolume = 1.0f;
	/**
	 * The actual volume of the media player. Will differ from the user volume
	 * when fading the volume.
	 */
	private float mCurrentVolume = 1.0f;

	private Method mIsWiredHeadsetOn;
	private Method mStartForeground;
	private Method mStopForeground;

	@Override
	public void onCreate()
	{
		HandlerThread thread = new HandlerThread("PlaybackService");
		thread.start();

		mTimeline = new SongTimeline();
		mTimeline.setCallback(this);
		mPendingSeek = mTimeline.loadState(this);
		if (mTimeline.isRepeating())
			mState |= FLAG_REPEAT;
		if (mTimeline.isShuffling())
			mState |= FLAG_SHUFFLE;

		ContextApplication.setService(this);

		mLooper = thread.getLooper();
		mHandler = new Handler(mLooper, this);
		mHandler.sendEmptyMessage(CREATE);
	}

	/**
	 * Show a Toast that notifies the user the Service is starting up. Useful
	 * to provide a quick response to play/pause and next events from widgets
	 * when we must initialize the service before acting on the event.
	 */
	private void showStartupToast()
	{
		Toast.makeText(this, R.string.starting, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onStart(Intent intent, int flags)
	{
		if (intent != null) {
			String action = intent.getAction();

			if (ACTION_TOGGLE_PLAYBACK.equals(action)) {
				go(0, false);
			} else if (ACTION_TOGGLE_PLAYBACK_DELAYED.equals(action)) {
				if (mHandler.hasMessages(CALL_GO, Integer.valueOf(0))) {
					mHandler.removeMessages(CALL_GO, Integer.valueOf(0));
					startActivity(new Intent(this, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
				} else {
					mHandler.sendMessageDelayed(mHandler.obtainMessage(CALL_GO, Integer.valueOf(0)), 400);
				}
			} else if (ACTION_NEXT_SONG.equals(action)) {
				// Preemptively broadcast an update in attempt to hasten UI
				// feedback.
				broadcastReplaceSong(0, getSong(+1));
				go(1, false);
			} else if (ACTION_NEXT_SONG_AUTOPLAY.equals(action)) {
				go(1, true);
			} else if (ACTION_NEXT_SONG_DELAYED.equals(action)) {
				if (mHandler.hasMessages(CALL_GO, Integer.valueOf(1))) {
					mHandler.removeMessages(CALL_GO, Integer.valueOf(1));
					startActivity(new Intent(this, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
				} else {
					mHandler.sendMessageDelayed(mHandler.obtainMessage(CALL_GO, Integer.valueOf(1)), 400);
				}
			} else if (ACTION_PREVIOUS_SONG.equals(action)) {
				go(-1, false);
			} else if (ACTION_PREVIOUS_SONG_AUTOPLAY.equals(action)) {
				go(-1, true);
			} else if (ACTION_PLAY_ITEMS.equals(action)) {
				mTimeline.chooseSongs(false, intent.getIntExtra("type", 3), intent.getLongExtra("id", -1));
				mHandler.sendEmptyMessage(TRACK_CHANGED);
			} else if (ACTION_ENQUEUE_ITEMS.equals(action)) {
				mTimeline.chooseSongs(true, intent.getIntExtra("type", 3), intent.getLongExtra("id", -1));
				mHandler.removeMessages(SAVE_STATE);
				mHandler.sendEmptyMessageDelayed(SAVE_STATE, 5000);
			} else if (ACTION_FINISH_ENQUEUEING.equals(action)) {
				mTimeline.finishEnqueueing();
			}
		}
	}

	@Override
	public void onDestroy()
	{
		ContextApplication.setService(null);

		super.onDestroy();

		if (mMediaPlayer != null) {
			mTimeline.saveState(this, mMediaPlayer.getCurrentPosition());

			unsetFlag(FLAG_PLAYING);
			mMediaPlayer.release();
			mMediaPlayer = null;
		}

		mLooper.quit();

		try {
			unregisterReceiver(mReceiver);
		} catch (IllegalArgumentException e) {
			// we haven't registered the receiver yet
		}

		// Re-enable the external receiver
		PackageManager manager = getPackageManager();
		manager.setComponentEnabledSetting(new ComponentName(this, MediaButtonReceiver.class), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);

		if (mWakeLock != null && mWakeLock.isHeld())
			mWakeLock.release();
	}

	public void startForegroundCompat(int id, Notification notification)
	{
		if (mStartForeground == null) {
			setForeground(true);
			mNotificationManager.notify(id, notification);
		} else {
			try {
				mStartForeground.invoke(this, Integer.valueOf(id), notification);
			} catch (InvocationTargetException e) {
				Log.w("VanillaMusic", e);
			} catch (IllegalAccessException e) {
				Log.w("VanillaMusic", e);
			}
		}
	}

	public void stopForegroundCompat(Boolean cancelNotification)
	{
		if (mStopForeground == null) {
			setForeground(false);
		} else {
			try {
				mStopForeground.invoke(this, cancelNotification);
			} catch (InvocationTargetException e) {
				Log.w("VanillaMusic", e);
			} catch (IllegalAccessException e) {
				Log.w("VanillaMusic", e);
			}
		}

		if (cancelNotification && mNotificationManager != null)
			mNotificationManager.cancel(NOTIFICATION_ID);
	}

	/**
	 * Return the SharedPreferences instance containing the PlaybackService
	 * settings, creating it if necessary.
	 */
	private SharedPreferences getSettings()
	{
		if (mSettings == null)
			mSettings = PreferenceManager.getDefaultSharedPreferences(this);
		return mSettings;
	}

	private void initialize()
	{
		ContextApplication.broadcast(new Intent(EVENT_INITIALIZED));

		mMediaPlayer = new MediaPlayer();
		mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		mMediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
		mMediaPlayer.setOnCompletionListener(this);
		mMediaPlayer.setOnErrorListener(this);

		mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

		try {
			mStartForeground = getClass().getMethod("startForeground", int.class, Notification.class);
			mStopForeground = getClass().getMethod("stopForeground", boolean.class);
		} catch (NoSuchMethodException e) {
			Log.d("VanillaMusic", "falling back to pre-2.0 Service APIs");
		}

		if (!"3".equals(Build.VERSION.SDK)) {
			try {
				mIsWiredHeadsetOn = mAudioManager.getClass().getMethod("isWiredHeadsetOn", (Class[])null);
			} catch (NoSuchMethodException e) {
				Log.d("VanillaMusic", "falling back to pre-1.6 AudioManager APIs");
			}
		}

		SharedPreferences settings = getSettings();
		settings.registerOnSharedPreferenceChangeListener(this);
		mHeadsetOnly = settings.getBoolean("headset_only", false);
		mNotificationMode = Integer.parseInt(settings.getString("notification_mode", "1"));
		mScrobble = settings.getBoolean("scrobble", false);
		float volume = settings.getFloat("volume", 1.0f);
		if (volume != 1.0f) {
			mCurrentVolume = mUserVolume = volume;
			mMediaPlayer.setVolume(volume, volume);
		}
		mIdleTimeout = settings.getBoolean("use_idle_timeout", false) ? settings.getInt("idle_timeout", 3600) : 0;

		PowerManager powerManager = (PowerManager)getSystemService(POWER_SERVICE);
		mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VanillaMusicSongChangeLock");

		mLoaded = true;

		setCurrentSong(0);

		if (mPendingSeek != 0)
			mMediaPlayer.seekTo(mPendingSeek);

		mHandler.sendEmptyMessage(POST_CREATE);
	}

	private void loadPreference(String key)
	{
		SharedPreferences settings = getSettings();
		if ("headset_pause".equals(key)) {
			mHeadsetPause = settings.getBoolean("headset_pause", true);
		} else if ("headset_only".equals(key)) {
			mHeadsetOnly = settings.getBoolean(key, false);
			if (mHeadsetOnly && isSpeakerOn())
				unsetFlag(FLAG_PLAYING);
		} else if ("remote_player".equals(key)) {
			// the preference is loaded in SongNotification class
			updateNotification(getSong(0));
		} else if ("notification_mode".equals(key)){
			mNotificationMode = Integer.parseInt(settings.getString("notification_mode", "1"));
			updateNotification(getSong(0));
		} else if ("scrobble".equals(key)) {
			mScrobble = settings.getBoolean("scrobble", false);
		} else if ("volume".equals(key)) {
			float volume = settings.getFloat("volume", 1.0f);
			mCurrentVolume = mUserVolume = volume;
			if (mMediaPlayer != null) {
				synchronized (mMediaPlayer) {
					mMediaPlayer.setVolume(volume, volume);
				}
			}
		} else if ("media_button".equals(key)) {
			MediaButtonHandler.getInstance().setUseHeadsetControls(settings.getBoolean("media_button", true));
			setupReceiver();
		} else if ("use_idle_timeout".equals(key) || "idle_timeout".equals(key)) {
			mIdleTimeout = settings.getBoolean("use_idle_timeout", false) ? settings.getInt("idle_timeout", 3600) : 0;
			userActionTriggered();
		}
	}

	private void broadcastReplaceSong(int delta, Song song)
	{
		Intent intent = new Intent(EVENT_REPLACE_SONG);
		intent.putExtra("pos", delta);
		intent.putExtra("song", song);
		mHandler.sendMessage(mHandler.obtainMessage(BROADCAST, intent));
	}

	void setFlag(int flag)
	{
		synchronized (mStateLock) {
			updateState(mState | flag);
		}
	}

	void unsetFlag(int flag)
	{
		synchronized (mStateLock) {
			updateState(mState & ~flag);
		}
	}

	private void updateState(int state)
	{
		state &= ALL_FLAGS;

		if ((state & FLAG_NO_MEDIA) != 0 || mHeadsetOnly && isSpeakerOn())
			state &= ~FLAG_PLAYING;

		Song song = getSong(0);
		if (song == null && (state & FLAG_PLAYING) != 0)
			return;
		if (song == null)
			state &= ~FLAG_REPEAT;

		int oldState = mState;
		mState = state;

		if (state != oldState || song != mLastSongBroadcast) {
			Intent intent = new Intent(EVENT_CHANGED);
			intent.putExtra("state", state);
			intent.putExtra("song", song);
			intent.putExtra("pos", mTimeline.getCurrentPosition());
			mHandler.sendMessage(mHandler.obtainMessage(BROADCAST, intent));

			if (mScrobble) {
				intent = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
				intent.putExtra("playing", (state & FLAG_PLAYING) != 0);
				if (song != null)
					intent.putExtra("id", (int)song.id);
				sendBroadcast(intent);
			}

			updateNotification(song);

			mLastSongBroadcast = song;
		}

		if ((oldState & PlaybackService.FLAG_SHUFFLE) == 0 && (state & PlaybackService.FLAG_SHUFFLE) != 0) {
			mTimeline.setShuffle(true);
			Toast.makeText(this, R.string.shuffle_enabling, Toast.LENGTH_LONG).show();
		} else if ((oldState & PlaybackService.FLAG_SHUFFLE) != 0 && (state & PlaybackService.FLAG_SHUFFLE) == 0) {
			mTimeline.setShuffle(false);
			Toast.makeText(this, R.string.shuffle_disabling, Toast.LENGTH_SHORT).show();
		}

		if ((oldState & PlaybackService.FLAG_REPEAT) == 0 && (state & PlaybackService.FLAG_REPEAT) != 0) {
			mTimeline.setRepeat(true);
			Toast.makeText(this, R.string.repeat_enabling, Toast.LENGTH_LONG).show();
		} else if ((oldState & PlaybackService.FLAG_REPEAT) != 0 && (state & PlaybackService.FLAG_REPEAT) == 0) {
			mTimeline.setRepeat(false);
			Toast.makeText(this, R.string.repeat_disabling, Toast.LENGTH_SHORT).show();
		}

		if ((state & FLAG_NO_MEDIA) != 0 && (oldState & FLAG_NO_MEDIA) == 0) {
			ContentResolver resolver = ContextApplication.getContext().getContentResolver();
			mMediaObserver = new MediaContentObserver(mHandler);
			resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mMediaObserver);
		} else if ((state & FLAG_NO_MEDIA) == 0 && (oldState & FLAG_NO_MEDIA) != 0) {
			ContentResolver resolver = ContextApplication.getContext().getContentResolver();
			resolver.unregisterContentObserver(mMediaObserver);
			mMediaObserver = null;
		}

		if ((state & FLAG_PLAYING) != 0 && (oldState & FLAG_PLAYING) == 0) {
			if (mNotificationMode != NEVER)
				startForegroundCompat(NOTIFICATION_ID, mNotification);
			if (mMediaPlayerInitialized) {
				synchronized (mMediaPlayer) {
					mMediaPlayer.start();
				}
			}
		} else if ((state & FLAG_PLAYING) == 0 && (oldState & FLAG_PLAYING) != 0) {
			stopForegroundCompat(false);
			if (mMediaPlayerInitialized) {
				synchronized (mMediaPlayer) {
					mMediaPlayer.pause();
				}
			}
		}

		if ((oldState & USER_MASK) != (state & USER_MASK))
			userActionTriggered();
	}

	private void updateNotification(Song song)
	{
		boolean shouldNotify = mNotificationMode == ALWAYS || mNotificationMode == WHEN_PLAYING && (mState & FLAG_PLAYING) != 0;
		if (song != null && shouldNotify) {
			mNotification = new SongNotification(song, (mState & FLAG_PLAYING) != 0);
			mNotificationManager.notify(NOTIFICATION_ID, mNotification);
		} else {
			stopForegroundCompat(true);
		}
	}

	boolean isSpeakerOn()
	{
		if (mAudioManager.isBluetoothA2dpOn() || mAudioManager.isBluetoothScoOn())
			return false;

		if (mIsWiredHeadsetOn != null) {
			try {
				if ((Boolean)mIsWiredHeadsetOn.invoke(mAudioManager, (Object[])null))
					return false;
			} catch (InvocationTargetException e) {
				Log.w("VanillaMusic", e);
			} catch (IllegalAccessException e) {
				Log.w("VanillaMusic", e);
			}
		}

		if (mPlugged)
			return false;

		// Why is there no true equivalent to this in Android 2.0?
		return (mAudioManager.getRouting(mAudioManager.getMode()) & AudioManager.ROUTE_SPEAKER) != 0;
	}

	/**
	 * Toggle a flag in the state on or off
	 *
	 * @param flag The flag to be toggled (FLAG_PLAYING, FLAG_SHUFFLE, or FLAG_REPEAT)
	 */
	public void toggleFlag(int flag)
	{
		synchronized (mStateLock) {
			if ((mState & flag) == 0)
				setFlag(flag);
			else
				unsetFlag(flag);
		}
	}

	/**
	 * Move <code>delta</code> places away from the current song.
	 */
	public void setCurrentSong(int delta)
        {
                setCurrentSong( delta, (delta!=0) );
        }

	public void setCurrentSong(int delta, boolean isUserAction)
	{
		if (mMediaPlayer == null)
			return;

		synchronized (mMediaPlayer) {
			mMediaPlayer.stop();
		}
		
		Song song = mTimeline.shiftCurrentSong(delta);
		if (song == null) {
			setFlag(FLAG_NO_MEDIA);
			return;
		} else if ((mState & FLAG_NO_MEDIA) != 0) {
			unsetFlag(FLAG_NO_MEDIA);
		}

		try {
			synchronized (mMediaPlayer) {
				mMediaPlayer.reset();
				mMediaPlayer.setDataSource(song.path);
				mMediaPlayer.prepare();
				if (!mMediaPlayerInitialized)
					mMediaPlayerInitialized = true;
			}
			if ((mState & FLAG_PLAYING) != 0)
				mMediaPlayer.start();
			// Ensure that we broadcast a change event even if we play the same
			// song again.
			mLastSongBroadcast = null;
			updateState(mState);
		} catch (IOException e) {
			Log.e("VanillaMusic", "IOException", e);
		}

		if (isUserAction)
			userActionTriggered();

		mHandler.sendEmptyMessage(PROCESS_SONG);
	}

	public void onCompletion(MediaPlayer player)
	{
		if (mWakeLock != null)
			mWakeLock.acquire();
		mHandler.sendEmptyMessage(TRACK_CHANGED);
		mHandler.sendEmptyMessage(RELEASE_WAKE_LOCK);
	}

	public boolean onError(MediaPlayer player, int what, int extra)
	{
		Log.e("VanillaMusic", "MediaPlayer error: " + what + " " + extra);
		mMediaPlayer.reset();
		Song song = getSong(+1);
		if (song != null && !song.query(true))
			setFlag(FLAG_NO_MEDIA);
		else
			mHandler.sendEmptyMessage(TRACK_CHANGED);
		return true;
	}

	/**
	 * Returns the song <code>delta</code> places away from the current
	 * position.
	 *
	 * @see SongTimeline#getSong(int)
	 */
	public Song getSong(int delta)
	{
		if (mTimeline == null)
			return null;

		return mTimeline.getSong(delta);
	}

	private void go(int delta, boolean autoPlay)
	{
		if (!mLoaded)
			showStartupToast();

		if (autoPlay) {
			synchronized (mStateLock) {
				mState |= FLAG_PLAYING;
			}
		}

		if (mLoaded) {
			if (delta == 0)
				toggleFlag(FLAG_PLAYING);
			else
				setCurrentSong(delta);
		} else {
			mHandler.sendMessage(mHandler.obtainMessage(GO, delta, 0));
		}
	}

	private class Receiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context content, Intent intent)
		{
			String action = intent.getAction();

			if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
				boolean oldPlugged = mPlugged;
				mPlugged = intent.getIntExtra("state", 0) != 0;
				if (mPlugged != oldPlugged && mHeadsetPause && !mPlugged || mHeadsetOnly && isSpeakerOn())
					unsetFlag(FLAG_PLAYING);
			} else if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
				if (MediaButtonHandler.getInstance().process(intent))
					abortBroadcast();
			}
		}
	};

	private class InCallListener extends PhoneStateListener {
		@Override
		public void onCallStateChanged(int state, String incomingNumber)
		{
			switch (state) {
			case TelephonyManager.CALL_STATE_RINGING:
			case TelephonyManager.CALL_STATE_OFFHOOK:
				MediaButtonHandler.getInstance().setInCall(true);
				if (!mPlayingBeforeCall) {
					synchronized (mStateLock) {
						if (mPlayingBeforeCall = (mState & FLAG_PLAYING) != 0)
							unsetFlag(FLAG_PLAYING);
					}
				}
				break;
			case TelephonyManager.CALL_STATE_IDLE:
				MediaButtonHandler.getInstance().setInCall(false);
				if (mPlayingBeforeCall) {
					setFlag(FLAG_PLAYING);
					mPlayingBeforeCall = false;
				}
				break;
			}
		}
	};

	private class MediaContentObserver extends ContentObserver {
		public MediaContentObserver(Handler handler)
		{
			super(handler);
		}

		@Override
		public void onChange(boolean selfChange)
		{
			setCurrentSong(0);
		}
	}

	public void onSharedPreferenceChanged(SharedPreferences settings, String key)
	{
		loadPreference(key);
	}

	private void setupReceiver()
	{
		if (mReceiver == null) {
			mReceiver = new Receiver();
		} else {
			try {
				unregisterReceiver(mReceiver);
			} catch (IllegalArgumentException e) {
			}
		}

		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_HEADSET_PLUG);
		if (MediaButtonHandler.getInstance().useHeadsetControls())
			filter.addAction(Intent.ACTION_MEDIA_BUTTON);
		filter.setPriority(2000);
		registerReceiver(mReceiver, filter);
	}

	private static final int GO = 0;
	private static final int POST_CREATE = 1;
	private static final int MEDIA_BUTTON = 2;
	private static final int CREATE = 3;
	/**
	 * This message is sent with a delay specified by a user preference. After
	 * this delay, assuming no new IDLE_TIMEOUT messages cancel it, playback
	 * will be stopped.
	 */
	private static final int IDLE_TIMEOUT = 4;
	private static final int TRACK_CHANGED = 5;
	private static final int RELEASE_WAKE_LOCK = 6;
	/**
	 * Decrease the volume gradually over five seconds, pausing when 0 is
	 * reached.
	 *
	 * arg1 should be the progress in the fade as a percentage, 1-100.
	 */
	private static final int FADE_OUT = 7;
	/**
	 * Calls {@link PlaybackService#go(int, boolean)} with the given delta.
	 *
	 * obj should an Integer representing the delta to pass to go.
	 */
	private static final int CALL_GO = 8;
	/**
	 * Broadcast the given intent with ContextApplication.
	 *
	 * obj should contain the intent to broadcast.
	 *
	 * @see ContextApplication#broadcast(Intent)
	 */
	private static final int BROADCAST = 9;
	private static final int SAVE_STATE = 12;
	private static final int PROCESS_SONG = 13;

	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MEDIA_BUTTON:
			toggleFlag(FLAG_PLAYING);
			break;
		case TRACK_CHANGED:
			setCurrentSong(+1, false);
			setFlag(FLAG_PLAYING);
			break;
		case RELEASE_WAKE_LOCK:
			if (mWakeLock != null && mWakeLock.isHeld())
				mWakeLock.release();
			break;
		case CALL_GO:
			int delta = (Integer)message.obj;
			go(delta, false);
			break;
		case GO:
			if (message.arg1 == 0)
				toggleFlag(FLAG_PLAYING);
			else
				setCurrentSong(message.arg1);
			break;
		case SAVE_STATE:
			// For unexpected terminations: crashes, task killers, etc.
			// In most cases onDestroy will handle this
			mTimeline.saveState(this, 0);
			break;
		case PROCESS_SONG:
			getSong(+2);
			mTimeline.purge();
			mHandler.removeMessages(SAVE_STATE);
			mHandler.sendEmptyMessageDelayed(SAVE_STATE, 5000);
			break;
		case CREATE:
			initialize();
			break;
		case POST_CREATE:
			mHeadsetPause = mSettings.getBoolean("headset_pause", true);
			setupReceiver();

			// Don't receive broadcasts through the external receiver now that
			// we get them in the Service's receiver
			PackageManager manager = getPackageManager();
			manager.setComponentEnabledSetting(new ComponentName(this, MediaButtonReceiver.class), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

			mCallListener = new InCallListener();
			TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			telephonyManager.listen(mCallListener, PhoneStateListener.LISTEN_CALL_STATE);
			break;
		case IDLE_TIMEOUT:
			if ((mState & FLAG_PLAYING) != 0)
				mHandler.sendMessage(mHandler.obtainMessage(FADE_OUT, 100, 0));
			break;
		case FADE_OUT:
			int progress = message.arg1 - 1;
			if (progress == 0) {
				unsetFlag(FLAG_PLAYING);
				mCurrentVolume = mUserVolume;
			} else {
				// Fade out on a x^4 curve. This produces a smoother
				// transition, since we are using raw sound intensities which
				// are heard by humans with a logarithmic scale. Don't fall
				// below .01 though: past this, hearing this music becomes
				// difficult or impossible.
				mCurrentVolume = Math.max((float)(Math.pow(progress / 100f, 4) * mUserVolume), .01f);
				
				mHandler.sendMessageDelayed(mHandler.obtainMessage(FADE_OUT, progress, 0), 50);
			}
			if (mMediaPlayer != null) {
				synchronized (mMediaPlayer) {
					mMediaPlayer.setVolume(mCurrentVolume, mCurrentVolume);
				}
			}
			break;
		case BROADCAST:
			ContextApplication.broadcast((Intent)message.obj);
			break;
		default:
			return false;
		}

		return true;
	}

	/**
	 * Returns the current service state. The state comprises several individual
	 * flags.
	 */
	public int getState()
	{
		synchronized (mStateLock) {
			return mState;
		}
	}

	/**
	 * Returns the current position in current song in milliseconds.
	 */
	public int getPosition()
	{
		if (mMediaPlayer == null)
			return 0;
		synchronized (mMediaPlayer) {
			return mMediaPlayer.getCurrentPosition();
		}
	}

	/**
	 * Returns the duration of the current song in milliseconds.
	 */
	public int getDuration()
	{
		if (mMediaPlayer == null)
			return 0;
		synchronized (mMediaPlayer) {
			return mMediaPlayer.getDuration();
		}
	}

	/**
	 * Returns the position of the current song in the song timeline.
	 *
	 * @see SongTimeline#getCurrentPosition()
	 */
	public int getTimelinePos()
	{
		return mTimeline.getCurrentPosition();
	}

	/**
	 * Seek to a position in the current song.
	 *
	 * @param progress Proportion of song completed (where 1000 is the end of the song)
	 */
	public void seekToProgress(int progress)
	{
		if (mMediaPlayer == null)
			return;
		synchronized (mMediaPlayer) {
			long position = (long)mMediaPlayer.getDuration() * progress / 1000;
			mMediaPlayer.seekTo((int)position);
		}
	}

	@Override
	public IBinder onBind(Intent intents)
	{
		return null;
	}

	/**
	 * Notify clients that a song in the timeline has been replaced.
	 */
	public void songReplaced(int delta, Song song)
	{
		broadcastReplaceSong(delta, song);
	}

	/**
	 * Remove the song with the given id from the timeline and advance to the
	 * next song if the given song is currently playing.
	 *
	 * @param id The MediaStore id of the song to remove.
	 * @see SongTimeline#removeSong(long)
	 */
	public void removeSong(long id)
	{
		boolean shouldAdvance = mTimeline.removeSong(id);
		if (shouldAdvance)
			setCurrentSong(0);
	}

	/**
	 * Resets the idle timeout countdown. Should be called by a user action
	 * has been trigger (new song chosen or playback toggled).
	 *
	 * If an idle fade out is actually in progress, aborts it and resets the
	 * volume.
	 */
	public void userActionTriggered()
	{
		mHandler.removeMessages(FADE_OUT);
		mHandler.removeMessages(IDLE_TIMEOUT);
		if (mIdleTimeout != 0)
			mHandler.sendEmptyMessageDelayed(IDLE_TIMEOUT, mIdleTimeout * 1000);

		if (mCurrentVolume != mUserVolume) {
			mCurrentVolume = mUserVolume;
			mMediaPlayer.setVolume(mCurrentVolume, mCurrentVolume);
		}
	}
}
