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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a Song backed by the MediaStore. Includes basic metadata and
 * utilities to retrieve songs from the MediaStore.
 */
public class Song implements Parcelable {
	/**
	 * Indicates that this song was randomly selected from all songs.
	 */
	public static final int FLAG_RANDOM = 0x1;

	/**
	 * A cache of covers that have been loaded with getCover().
	 */
	private static final Cache<Bitmap> mCoverCache = new Cache<Bitmap>(10);

	/**
	 * A cache of randomly selected songs.
	 */
	private static ArrayList<Song> mRandomSongs = new ArrayList<Song>();
	private static ArrayList<Long> mRandomSongIds = new ArrayList<Long>();
	private static int mRandomSongIdx = -1;
	private static int mRandomSongLastPopulatedIdx = -1;
	private static int mMediaStoreSongCountCache = -1;
	private static final int mRandomSongLastPopulationSize = 20;

	private static final String[] FILLED_PROJECTION = {
		MediaStore.Audio.Media._ID,
		MediaStore.Audio.Media.DATA,
		MediaStore.Audio.Media.TITLE,
		MediaStore.Audio.Media.ALBUM,
		MediaStore.Audio.Media.ARTIST,
		MediaStore.Audio.Media.ALBUM_ID
		};

	/**
	 * Id of this song in the MediaStore
	 */
	public long id;
	/**
	 * Id of this song's album in the MediaStore
	 */
	public long albumId;

	/**
	 * Path to the data for this song
	 */
	public String path;

	/**
	 * Song title
	 */
	public String title;
	/**
	 * Album name
	 */
	public String album;
	/**
	 * Artist name
	 */
	public String artist;

	/**
	 * Song flags. Currently FLAG_RANDOM or 0.
	 */
	public int flags;

	public static void onMediaStoreContentsChanged()
	{
		mMediaStoreSongCountCache = -1;
		mRandomSongIdx = -1;
	}
	
	public static int getMediaStoreSongCount()
	{
		if (mMediaStoreSongCountCache == -1) {
			ContentResolver resolver = ContextApplication.getContext().getContentResolver();
			Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
			String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
			Cursor cursor = resolver.query(media, new String[] { MediaStore.Audio.Media._ID }, selection, null, null);
	
			mMediaStoreSongCountCache = cursor.getCount(); 
		}
		
		return mMediaStoreSongCountCache;
	}
	
	/**
	 * @return true if it's possible to retrieve any songs, otherwise false. For example, false
	 * could be returned if there are no songs in the library.
	 */
	public static boolean isSongAvailable()
	{
		return getMediaStoreSongCount() > 0;
	}
	
	/**
	 * Returns a song randomly selected from all the songs in the Android
	 * MediaStore.
	 */
	public static Song randomSong()
	{
		if (mRandomSongIdx == -1 || mRandomSongIdx == mRandomSongIds.size())
		{
			mRandomSongIds.clear();
			
			int mediaStoreSongCount = getMediaStoreSongCount();
			mRandomSongIds.ensureCapacity(mediaStoreSongCount);
			
			ContentResolver resolver = ContextApplication.getContext().getContentResolver();
			Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
			String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
			Cursor cursor = resolver.query(media, new String[] { MediaStore.Audio.Media._ID }, selection, null, null);
	
			if (cursor != null) {
				for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
					mRandomSongIds.add(new Long(cursor.getLong(0)));
				}

				Collections.shuffle(mRandomSongIds);
				
				cursor.close();
			}
			else {
				return null;
			}
			
			mRandomSongIdx = 0;
			mRandomSongLastPopulatedIdx = -1;
		}
		
		if (mRandomSongIdx > mRandomSongLastPopulatedIdx) {
			mRandomSongs.clear();
			mRandomSongs.ensureCapacity(mRandomSongLastPopulationSize);
			
			ContentResolver resolver = ContextApplication.getContext().getContentResolver();
			Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

			List<Long> selectedIds = mRandomSongIds.subList(mRandomSongIdx, mRandomSongIdx + mRandomSongLastPopulationSize);
			
			StringBuilder selection = new StringBuilder("_ID IN (");

			boolean first = true;
			for (Long id : selectedIds) {
				if (!first)
					selection.append(",");
				
				first = false;
				
				selection.append(id.toString());
			}

			selection.append(")");
			
			Cursor cursor = resolver.query(media, FILLED_PROJECTION, selection.toString(), null, null);
			
			if (cursor == null) {
				mRandomSongIdx = -1;
				return null;
			}
			
			long count = cursor.getCount();
			if (count > 0) {
				assert(count <= mRandomSongLastPopulationSize);
				
				for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
					Song newSong = new Song(-1);
					newSong.populate(cursor);
					newSong.flags |= FLAG_RANDOM;
					mRandomSongs.add(newSong);
				}
			}
			
			cursor.close();
			
			mRandomSongLastPopulatedIdx = mRandomSongIdx + mRandomSongLastPopulationSize;  
		}

		Song result = mRandomSongs.get(mRandomSongIdx % mRandomSongLastPopulationSize);
		
		++mRandomSongIdx;
		
		return result;
	}

	/**
	 * Initialize the song with the specified id. Call populate to fill fields
	 * in the song.
	 */
	public Song(long id)
	{
		this.id = id;
	}
	
	/**
	 * Initialize the song with the specified id and flags. Call populate to
	 * fill fields in the song.
	 */
	public Song(long id, int flags)
	{
		this.id = id;
		this.flags = flags;
	}

	/**
	 * Populate fields with data from the supplied cursor.
	 *
	 * @param cursor Cursor queried with FILLED_PROJECTION projection
	 */
	private void populate(Cursor cursor)
	{
		id = cursor.getLong(0);
		path = cursor.getString(1);
		title = cursor.getString(2);
		album = cursor.getString(3);
		artist = cursor.getString(4);
		albumId = cursor.getLong(5);
	}

	/**
	 * Copies the fields from the given Song to this Song.
	 *
	 * @param other The Song to copy from.
	 */
	public void copy(Song other)
	{
		if (other == null) {
			id = -1;
			return;
		}

		id = other.id;
		albumId = other.albumId;
		path = other.path;
		title = other.title;
		album = other.album;
		artist = other.artist;
		flags = other.flags;
	}

	/**
	 * Query the MediaStore, if necessary, to fill this Song's fields.
	 *
	 * @param force Query even if fields have already been populated
	 * @return true if fields have been populated, false otherwise
	 */
	public boolean query(boolean force)
	{
		if (path != null && !force)
			return true;
		if (id == -1)
			return false;

		ContentResolver resolver = ContextApplication.getContext().getContentResolver();
		Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
		String selection = MediaStore.Audio.Media._ID + '=' + id;
		Cursor cursor = resolver.query(media, FILLED_PROJECTION, selection, null, null);

		id = -1;

		if (cursor != null) {
			if (cursor.moveToNext())
				populate(cursor);
			cursor.close();
		}

		return id != -1;
	}

	/**
	 * Get the id of the given song.
	 *
	 * @param song The Song to get the id from.
	 * @return The id, or 0 if the given song is null.
	 */
	public static long getId(Song song)
	{
		if (song == null)
			return 0;
		return song.id;
	}

	public static Parcelable.Creator<Song> CREATOR = new Parcelable.Creator<Song>() {
		public Song createFromParcel(Parcel in)
		{
			return new Song(in);
		}

		public Song[] newArray(int size)
		{
			return new Song[size];
		}
	};

	public Song(Parcel in)
	{
		id = in.readLong();
		albumId = in.readLong();
		path = in.readString();
		title = in.readString();
		album = in.readString();
		artist = in.readString();
	}

	public void writeToParcel(Parcel out, int flags)
	{
		out.writeLong(id);
		out.writeLong(albumId);
		out.writeString(path);
		out.writeString(title);
		out.writeString(album);
		out.writeString(artist);
	}

	public int describeContents()
	{
		return 0;
	}

	private static final BitmapFactory.Options BITMAP_OPTIONS = new BitmapFactory.Options();

	static {
		BITMAP_OPTIONS.inPreferredConfig = Bitmap.Config.RGB_565;
		BITMAP_OPTIONS.inDither = false;
	}

	/**
	 * Query the album art for this song.
	 *
	 * @return The album art or null if no album art could be found
	 */
	public Bitmap getCover()
	{
		if (id == -1)
			return null;

		// Query the cache for the cover
		Bitmap cover = mCoverCache.get(id);
		if (cover != null)
			return cover;

		Context context = ContextApplication.getContext();
		ContentResolver res = context.getContentResolver();

		// Query the MediaStore content provider
		cover = getCoverFromMediaFile(res);

		// If that fails, try using MediaScanner directly
		if (cover == null)
			cover = getCoverFromMediaUsingMediaScanner(res);

		// Fall back to the official, documented, slow way.
		if (cover == null)
			cover = getCoverFromMediaStoreCache(res);

		Bitmap deletedCover = mCoverCache.put(id, cover);
		if (deletedCover != null)
			deletedCover.recycle();

		return cover;
	}

	/**
	 * Attempts to read the album art directly from a media file using the
	 * media ContentProvider.
	 *
	 * @param resolver A ContentResolver to use.
	 * @return The album art or null if no album art could be found.
	 */
	private Bitmap getCoverFromMediaFile(ContentResolver resolver)
	{
		// Use undocumented API to extract the cover from the media file from Eclair
		// See http://android.git.kernel.org/?p=platform/packages/apps/Music.git;a=blob;f=src/com/android/music/MusicUtils.java;h=d1aea0660009940a0160cb981f381e2115768845;hb=0749a3f1c11e052f97a3ba60fd624c9283ee7331#l986
		Bitmap cover = null;

		try {
			Uri uri = Uri.parse("content://media/external/audio/media/" + id + "/albumart");
			ParcelFileDescriptor parcelFileDescriptor = resolver.openFileDescriptor(uri, "r");
			if (parcelFileDescriptor != null) {
				FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
				cover = BitmapFactory.decodeFileDescriptor(fileDescriptor, null, BITMAP_OPTIONS);
			}
		} catch (IllegalStateException e) {
		} catch (FileNotFoundException e) {
		}

		return cover;
	}

	/**
	 * Obtain the cover from a media file using the private MediaScanner API
	 *
	 * @param resolver A ContentResolver to use.
	 * @return The cover or null if the file has no cover art or the art could
	 * not be loaded using this method.
	 */
	private Bitmap getCoverFromMediaUsingMediaScanner(ContentResolver resolver)
	{
		Bitmap cover = null;

		// This is a private API, so do everything using reflection
		// see http://android.git.kernel.org/?p=platform/packages/apps/Music.git;a=blob;f=src/com/android/music/MusicUtils.java;h=ea2079435ca5e2c6834c9f6f02d07fe7621e0fd9;hb=aae2791ffdd8923d99242f2cf453eb66116fd6b6#l1044
		try {
			// Attempt to open the media file in read-only mode
			Uri uri = Uri.fromFile(new File(path));
			FileDescriptor fileDescriptor = resolver.openFileDescriptor(uri, "r").getFileDescriptor();

			if (fileDescriptor != null) {
				// Construct a MediaScanner
				Class<?> mediaScannerClass = Class.forName("android.media.MediaScanner");
				Constructor<?> mediaScannerConstructor = mediaScannerClass.getDeclaredConstructor(Context.class);
				Object mediaScanner = mediaScannerConstructor.newInstance(ContextApplication.getContext());

				// Call extractAlbumArt(fileDescriptor)
				Method method = mediaScannerClass.getDeclaredMethod("extractAlbumArt", FileDescriptor.class);
				byte[] artBinary = (byte[]) method.invoke(mediaScanner, fileDescriptor);

				// Convert the album art to a bitmap
				if (artBinary != null)
					cover = BitmapFactory.decodeByteArray(artBinary, 0, artBinary.length, BITMAP_OPTIONS);
			}
		} catch (Exception e) {
			// Swallow every exception and return an empty cover if we can't do it due to the API not being there anymore
		}

		return cover;
	}

	/**
	 * Get the cover from the media store cache, the documented way.
	 *
	 * @param resolver A ContentResolver to use.
	 * @return The cover or null if the MediaStore has no cover for the given
	 * song.
	 */
	private Bitmap getCoverFromMediaStoreCache(ContentResolver resolver)
	{
		Uri media = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
		String[] albumProjection = {MediaStore.Audio.Albums.ALBUM_ART};
		String albumSelection = MediaStore.Audio.Albums._ID + '=' + albumId;

		Cursor cursor = resolver.query(media, albumProjection, albumSelection, null, null);
		if (cursor != null) {
			if (cursor.moveToNext())
				return BitmapFactory.decodeFile(cursor.getString(0), BITMAP_OPTIONS);
			cursor.close();
		}

		return null;
	}
}