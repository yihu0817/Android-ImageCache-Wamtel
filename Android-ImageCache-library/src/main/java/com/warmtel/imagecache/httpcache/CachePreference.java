package com.warmtel.imagecache.httpcache;

import android.content.Context;
import android.content.SharedPreferences;

public class CachePreference {
	public static final String NAME = "PREFERENCE_API_CACHE";
	private SharedPreferences mPreferences;
	private static CachePreference mInstance;

	public synchronized static CachePreference getInstance(Context context) {
		if (mInstance == null) {
			mInstance = new CachePreference(context);
		}
		return mInstance;
	}

	private CachePreference(Context context) {
		mPreferences = context.getSharedPreferences(NAME, 0);
	}

	public void putCache(String key, String value) {
		mPreferences.edit().putString(key, value).commit();
	}

	public String getCache(String key) {
		return mPreferences.getString(key, null);
	}

	public void clearCache() {
		mPreferences.edit().clear().commit();
	}
}
