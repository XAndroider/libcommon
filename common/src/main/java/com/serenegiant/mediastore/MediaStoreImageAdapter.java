package com.serenegiant.mediastore;
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2020 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.serenegiant.common.R;
import com.serenegiant.graphics.BitmapHelper;
import com.serenegiant.utils.ThreadPool;

import java.io.IOException;

import static com.serenegiant.mediastore.MediaStoreUtils.*;

/**
 * MediaStore内の静止画をViewPagerで表示するためのPagerAdapter実装
 * こっちはサムネイルではなくファイルからBitmapを指定サイズで読み込む
 */
public class MediaStoreImageAdapter extends PagerAdapter {
	private static final boolean DEBUG = false;	// FIXME 実働時はfalseにすること
	private static final String TAG = MediaStoreImageAdapter.class.getSimpleName();

	private final LayoutInflater mInflater;
	private final int mLayoutId;
	private final ContentResolver mCr;
	private final MyAsyncQueryHandler mQueryHandler;
	protected boolean mDataValid;
//	protected int mRowIDColumn;
	protected ChangeObserver mChangeObserver;
	protected DataSetObserver mDataSetObserver;
	private Cursor mCursor;
	private String mSelection = SELECTIONS[MEDIA_IMAGE];	// 静止画のみ有効
	private String[] mSelectionArgs = null;
	@NonNull
	private final MediaInfo info = new MediaInfo();

	private boolean mShowTitle;

	public MediaStoreImageAdapter(@NonNull final Context context,
		@LayoutRes final int id_layout) {

		this(context, id_layout, true);
	}

	public MediaStoreImageAdapter(@NonNull final Context context,
		@LayoutRes final int id_layout, final boolean needQuery) {

		mInflater = LayoutInflater.from(context);
		mLayoutId = id_layout;
		mCr = context.getContentResolver();
		mQueryHandler = new MyAsyncQueryHandler(mCr, this);
		ThreadPool.preStartAllCoreThreads();
		if (needQuery) {
			refresh();
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			changeCursor(null);
		} finally {
			super.finalize();
		}
	}

	@Override
	public int getCount() {
		if (mDataValid && mCursor != null) {
			return mCursor.getCount();
		} else {
			return 0;
		}
	}

	@NonNull
	@Override
	public Object instantiateItem(@NonNull final ViewGroup container,
		final int position) {

		if (DEBUG) Log.v(TAG, "instantiateItem:position=" + position);
		final View view = mInflater.inflate(mLayoutId, container, false);
		ViewHolder holder;
		if (view != null) {
			container.addView(view);
			holder = (ViewHolder)view.getTag();
			if (holder == null) {
				holder = new ViewHolder();
			}
			final TextView tv = holder.mTitleView = view.findViewById(R.id.title);
			final ImageView iv = holder.mImageView = view.findViewById(R.id.thumbnail);
			info.loadFromCursor(getCursor(position));
			// ローカルキャッシュ
			Drawable drawable = iv.getDrawable();
			if (!(drawable instanceof LoaderDrawable)) {
				drawable = createLoaderDrawable(mCr, info);
				iv.setImageDrawable(drawable);
			}
			((LoaderDrawable)drawable).startLoad(info.mediaType, 0, info.id);
			if (tv != null) {
				tv.setVisibility(mShowTitle ? View.VISIBLE : View.GONE);
				if (mShowTitle) {
					tv.setText(info.title);
				}
			}
		}
		return view;
	}

	@Override
	public void destroyItem(@NonNull final ViewGroup container,
		final int position, @NonNull final Object object) {

		if (DEBUG) Log.v(TAG, "destroyItem:position=" + position);
		if (object instanceof View) {
			container.removeView((View)object);
		}
	}

	@Override
	public int getItemPosition(@NonNull final Object object) {
		// FIXME ここはobject=ViewからMediaInfo#idを取得してpositionを検索し直さないとだめかも
		return super.getItemPosition(object);
	}

	public int getItemPositionFromID(final long id) {
		if (DEBUG) Log.v(TAG, "getItemPositionFromID:id=" + id);
		int result = -1;
		final Cursor cursor = mCr.query(QUERY_URI, PROJ_MEDIA, mSelection, mSelectionArgs, null);
		if (cursor != null) {
			try {
				if (cursor.moveToFirst()) {
					for (int ix = 0 ; ; ix++) {
						if (cursor.getLong(PROJ_INDEX_ID) == id) {
							if (DEBUG) Log.v(TAG, "getItemPositionFromID:found=" + ix);
							result = ix;
							break;
						}
						if (!cursor.moveToNext()) break;
					}
				}
			} finally {
				cursor.close();
			}
		}
		return result;
	}

	@Override
	public boolean isViewFromObject(@NonNull final View view,
		@NonNull final Object object) {

		return view.equals(object);
	}

	/**
	 * get MediaInfo at specified position
	 * @param position
	 * @return
	 */
	@NonNull
	public MediaInfo getMediaInfo(final int position) {
		return getMediaInfo(position, null);
	}

	/**
	 * get MediaInfo at specified position
	 * @param position
	 * @param info
	 * @return
	 */
	@NonNull
	public synchronized MediaInfo getMediaInfo(
		final int position, @Nullable final MediaInfo info) {

		final MediaInfo _info = info != null ? info : new MediaInfo();

/*		// if you don't need to frequently call this method, temporary query may be better to reduce memory usage.
		// but it will take more time.
		final Cursor cursor = mCr.query(
			ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, getItemId(position)),
			PROJ_IMAGE, mSelection, mSelectionArgs, MediaStore.Images.Media.DEFAULT_SORT_ORDER);
		if (cursor != null) {
			try {
				if (cursor.moveToFirst()) {
					info = readMediaInfo(cursor, new MediaInfo());
				}
			} finally {
				cursor.close();
			}
		} */
		if (mCursor == null) {
			mCursor = mCr.query(
				QUERY_URI, PROJ_MEDIA,
				mSelection, mSelectionArgs, null);
		}
		if (mCursor.moveToPosition(position)) {
			_info.loadFromCursor(mCursor);
		}
		return _info;
	}

	protected void changeCursor(@Nullable final Cursor cursor) {
		final Cursor old = swapCursor(cursor);
		if ((old != null) && !old.isClosed()) {
			old.close();
		}
	}

	protected Cursor getCursor(final int position) {
		if (mDataValid && mCursor != null) {
			mCursor.moveToPosition(position);
			return mCursor;
		} else {
			return null;
  		}
	}

	protected Cursor swapCursor(final Cursor newCursor) {
		if (newCursor == mCursor) {
			return null;
		}
		Cursor oldCursor = mCursor;
		if (oldCursor != null) {
			if (mChangeObserver != null) {
				oldCursor.unregisterContentObserver(mChangeObserver);
			}
			if (mDataSetObserver != null) {
				oldCursor.unregisterDataSetObserver(mDataSetObserver);
			}
		}
		mCursor = newCursor;
		if (newCursor != null) {
			if (mChangeObserver != null) {
				newCursor.registerContentObserver(mChangeObserver);
			}
			if (mDataSetObserver != null) {
				newCursor.registerDataSetObserver(mDataSetObserver);
			}
//			mRowIDColumn = newCursor.getColumnIndexOrThrow("_id");
			mDataValid = true;
			// notify the observers about the new cursor
			notifyDataSetChanged();
		} else {
//			mRowIDColumn = -1;
			mDataValid = false;
			// notify the observers about the lack of a data set
			notifyDataSetInvalidated();
		}
		return oldCursor;
	}

	public void notifyDataSetInvalidated() {
//		mDataSetObservable.notifyInvalidated();
	}

	public void refresh() {
		mQueryHandler.requery();
	}

	protected LoaderDrawable createLoaderDrawable(
		@NonNull final ContentResolver cr, @NonNull final MediaInfo info) {

		return new ImageLoaderDrawable(cr, info.width, info.height);
	}

	private static final class ViewHolder {
		TextView mTitleView;
		ImageView mImageView;
	}

	private static final class MyAsyncQueryHandler extends AsyncQueryHandler {
		@NonNull
		private final MediaStoreImageAdapter mAdapter;

		public MyAsyncQueryHandler(
			@NonNull final ContentResolver cr,
			@NonNull final MediaStoreImageAdapter adapter) {

			super(cr);
			mAdapter = adapter;
		}

		public void requery() {
			synchronized (mAdapter) {
				startQuery(0, mAdapter, QUERY_URI, PROJ_MEDIA,
					mAdapter.mSelection, mAdapter.mSelectionArgs, null);
			}
		}

		@Override
		protected void onQueryComplete(final int token,
			final Object cookie, final Cursor cursor) {

//			super.onQueryComplete(token, cookie, cursor);	// this is empty method
			final Cursor oldCursor = mAdapter.swapCursor(cursor);
			if ((oldCursor != null) && !oldCursor.isClosed())
				oldCursor.close();
		}

	}

	private class ChangeObserver extends ContentObserver {
		public ChangeObserver() {
			super(new Handler());
		}

		@Override
		public boolean deliverSelfNotifications() {
			return true;
		}

		@Override
		public void onChange(boolean selfChange) {
			refresh();
		}
	}

	private class MyDataSetObserver extends DataSetObserver {
		@Override
		public void onChanged() {
			mDataValid = true;
			notifyDataSetChanged();
		}

		@Override
		public void onInvalidated() {
			mDataValid = false;
			notifyDataSetInvalidated();
		}
	}

	private static class ImageLoaderDrawable extends LoaderDrawable {
		public ImageLoaderDrawable(final ContentResolver cr,
			final int width, final int height) {

			super(cr, width, height);
		}

		@Override
		protected ImageLoader createImageLoader() {
			return new MyImageLoader(this);
		}

		@Override
		protected Bitmap checkCache(final int groupId, final long id) {
			return null;
		}
	}

	private static class MyImageLoader extends ImageLoader {
		public MyImageLoader(final ImageLoaderDrawable parent) {
			super(parent);
		}

		@Override
		protected Bitmap loadBitmap(@NonNull final ContentResolver cr,
			final int mediaType, final int groupId, final long id,
			final int requestWidth, final int requestHeight) {

			Bitmap result = null;
			try {
				result = BitmapHelper.asBitmap(cr, id, requestWidth, requestHeight);
				if (result != null) {
					final int w = result.getWidth();
					final int h = result.getHeight();
					final Rect bounds = new Rect();
					mParent.copyBounds(bounds);
					final int cx = bounds.centerX();
					final int cy = bounds.centerY();
					bounds.set(cx - w / 2, cy - h / w, cx + w / 2, cy + h / 2);
					mParent.onBoundsChange(bounds);
				}
			} catch (final IOException e) {
				Log.w(TAG, e);
			}
			return result;
		}
	}

}
