package com.warmtel.imagecache;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 解决 类似 ListView 与 GridView 等视图组件在使用AsyncTask异步加载图片滚动时出现图片错乱问题， 
 * AsyncTask异步多线程技术，多任务同步执行
 * 内存缓存实现LRUCache
 * 文件缓存DiskCache
 * 回调接口
 * @author Viktor.zhou
 * 
 */
public class AsyncMemoryFileCacheImageLoader {
	private static final int CORE_POOL_SIZE = 15;
	private static final int MAXIMUM_POOL_SIZE = 150;
	private static final int KEEP_ALIVE_TIME = 10;
	private static final int DISK_CACHE_SIZE = 10 * 1024 * 1024; //10M
	/**
	 * 图片缓存技术的核心类，用于缓存所有下载好的图片，在程序内存达到设定值时会将最少最近使用的图片移除掉。
	 */
	private LruCache<String, Bitmap> mMemoryCache;
	private DiskLruCache mDiskLruCache = null;
	/**自定义同步执行的多任务*/
	private Executor mExec = null;

	/**
	 * 定义回调接口
	 */
	public interface Callback{
		public void onSuccess();
		public void onError();
	}

	private static AsyncMemoryFileCacheImageLoader mAsycnHttpImageView = null;

	public static AsyncMemoryFileCacheImageLoader getInstance(Context context) {
		if (mAsycnHttpImageView == null) {
			mAsycnHttpImageView = new AsyncMemoryFileCacheImageLoader(context);
		}
		return mAsycnHttpImageView;
	}

	private AsyncMemoryFileCacheImageLoader(Context context) {
		// 获取应用程序最大可用内存
		int maxMemory = (int) Runtime.getRuntime().maxMemory();
		int cacheSize = maxMemory / 32;
		// 设置图片缓存大小为程序最大可用内存的1/8
		mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
			@Override
			protected int sizeOf(String key, Bitmap bitmap) {
				return bitmap.getByteCount();
			}
		};
		
		try {
			File cacheDir = getDiskCacheDir(context, "bitmap");
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			mDiskLruCache = DiskLruCache.open(cacheDir, getAppVersion(context), 1, DISK_CACHE_SIZE);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void setThreadPoolExecutor() {
		setThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE);
	}
	/**
	 * 设置AsyncTask线程池中并发执行线程个数
	 */
	public void setThreadPoolExecutor(int corePoolSize, int maximumPoolSize) {
		mExec = new ThreadPoolExecutor(corePoolSize, maximumPoolSize,
				KEEP_ALIVE_TIME, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>());
	}

	/**
	 * 通常类似 ListView 与 GridView 等视图组件在使用上面演示的AsyncTask 方法时会同时带来另外一个问题。
	 * 为了更有效的处理内存，那些视图的子组件会在用户滑动屏幕时被循环使用。如果每一个子视图都触发一个AsyncTask ，
	 * 那么就无法确保当前视图在结束task时，分配的视图已经进入循环队列中给另外一个子视图进行重用。 而且, 无法确保所有的
	 * 异步任务能够按顺序执行完毕。
	 *
	 * 无回调
	 * 指定图片大小
	 *
	 * @param imageUrl
	 * @param imageView
	 * @param resId
	 *            默认图片资源
	 * @param width
	 * @param heigh
	 *            width和height为0表示按原图大小返回
	 */
	public void loadBitmap(Resources res, String imageUrl, ImageView imageView,int resId, int width, int height) {
		inintLoadBitmap(res,imageUrl,imageView,resId,width,height,null);
	}

	/**
	 * 有回调
	 * 指定图片大小
	 * @param res
	 * @param imageUrl
	 * @param imageView
	 * @param resId
	 * @param width
	 * @param height
	 * @param callback
	 */
	public void loadBitmap(Resources res, String imageUrl, ImageView imageView,int resId, int width, int height,Callback callback) {
		inintLoadBitmap(res,imageUrl,imageView,resId,width,height,callback);
	}

	/**
	 *
	 * 无回调
	 * 默认图片大小
	 *
	 * @param res
	 * @param imageUrl
	 * @param imageView
	 * @param callback
	 *  widht height 默认为 0
	 *  R.drawable.transparent：  在res/values文件夹下定义drawables.xml文件，设置如下值：表示默认图片透明
	 *  <item name="transparent" type="drawable">#00000000</item>
	 */
	public void loadBitmap(Resources res, String imageUrl, ImageView imageView) {
		inintLoadBitmap(res,imageUrl,imageView,R.drawable.transparent,0,0,null);
	}

	/**
	 * 有回调
	 * 默认图片大小
	 *
	 * @param res
	 * @param imageUrl
	 * @param imageView
	 * @param callback
	 *  widht height 默认为 0
	 *  R.drawable.transparent：  在res/values文件夹下定义drawables.xml文件，设置如下值：表示默认图片透明
	 *  <item name="transparent" type="drawable">#00000000</item>
	 */
	public void loadBitmap(Resources res, String imageUrl, ImageView imageView,Callback callback) {
		inintLoadBitmap(res,imageUrl,imageView,R.drawable.transparent,0,0,callback);
	}

	public void inintLoadBitmap(Resources res, String imageUrl, ImageView imageView,int resId, int width, int height,Callback callback){
		Bitmap bitmap = getBitmapFromMemoryCache(imageUrl);
		if (bitmap != null) {
//		Log.v("tag","从内存取图片 " + imageUrl);
			imageView.setImageBitmap(bitmap);
			if(callback != null){
				callback.onSuccess();
			}
			return;
		}

		if (cancelPotentialWork(imageUrl, imageView)) {
			BitmapWorkerTask task = new BitmapWorkerTask(imageView,callback);

			AsyncDrawable asyncDrawable = new AsyncDrawable(res,BitmapFactory.decodeResource(res, resId), task);
			imageView.setImageDrawable(asyncDrawable);

			if (mExec == null) {
				task.execute(imageUrl, String.valueOf(width),String.valueOf(height));
			} else {
				task.executeOnExecutor(mExec, imageUrl, String.valueOf(width),String.valueOf(height));
			}

		}
	}

	/**
	 * 将一张图片存储到LruCache中。
	 * 
	 * @param key
	 *            LruCache的键，这里传入图片的URL地址。
	 * @param bitmap
	 *            LruCache的键，这里传入从网络上下载的Bitmap对象。
	 */
	public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
		if (getBitmapFromMemoryCache(key) == null) {
//			Logs.v("添加到内存 ");
			mMemoryCache.put(key, bitmap);
		}
	}

	/**
	 * 从LruCache中获取一张图片，如果不存在就返回null。
	 * 
	 * @param key
	 *            LruCache的键，这里传入图片的URL地址。
	 * @return 对应传入键的Bitmap对象，或者null。
	 */
	public Bitmap getBitmapFromMemoryCache(String key) {
		return mMemoryCache.get(key);
	}

	public static boolean cancelPotentialWork(String imageUrl,
			ImageView imageView) {
		final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

		if (bitmapWorkerTask != null) {
			final String bitmapData = bitmapWorkerTask.imageUrl;
			if (!bitmapData.equals(imageUrl)) {
				// Cancel previous task
				bitmapWorkerTask.cancel(true);
			} else {
				// The same work is already in progress
				return false;
			}
		}
		// No task associated with the ImageView, or an existing task was
		// cancelled
		return true;
	}

	private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
		if (imageView != null) {
			final Drawable drawable = imageView.getDrawable();
			if (drawable instanceof AsyncDrawable) {
				final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
				return asyncDrawable.getBitmapWorkerTask();
			}
		}
		return null;
	}

	/**
	 * 创建一个专用的Drawable的子类来储存返回工作任务的引用。在这种情况下，当任务完成时BitmapDrawable会被使用
	 * 
	 */
	static class AsyncDrawable extends BitmapDrawable {
		private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

		public AsyncDrawable(Resources res, Bitmap bitmap,BitmapWorkerTask bitmapWorkerTask) {
			super(res, bitmap);
			bitmapWorkerTaskReference = new WeakReference<BitmapWorkerTask>(bitmapWorkerTask);
		}

		public BitmapWorkerTask getBitmapWorkerTask() {
			return (BitmapWorkerTask) bitmapWorkerTaskReference.get();
		}
	}

	class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap> {
		private final WeakReference<ImageView> imageViewReference;
		private String imageUrl = "";
		private Callback callback;
		public BitmapWorkerTask(ImageView imageView,Callback callback) {
			// Use a WeakReference to ensure the ImageView can be garbage
			// collected
			this.callback = callback;
			imageViewReference = new WeakReference<ImageView>(imageView);
		}

		public BitmapWorkerTask(ImageView imageView) {
			imageViewReference = new WeakReference<ImageView>(imageView);
		}
		// Decode image in background.
		@Override
		protected Bitmap doInBackground(String... params) {
			imageUrl = params[0];
			int reqWidth = Integer.parseInt(params[1]);
			int reqHeight = Integer.parseInt(params[2]);
//			return decodeSampledBitmapFromStream(data, widht, height);
			
            FileDescriptor fileDescriptor = null;  
            FileInputStream fileInputStream = null;  
            DiskLruCache.Snapshot snapShot = null;
            try {  
                // 生成图片URL对应的key  
                final String key = hashKeyForDisk(imageUrl);  
                // 查找key对应的缓存  
                snapShot = mDiskLruCache.get(key);  
                if (snapShot == null) {  
                    // 如果没有找到对应的缓存，则准备从网络上请求数据，并写入缓存  
                    DiskLruCache.Editor editor = mDiskLruCache.edit(key);  
                    if (editor != null) {  
                        OutputStream outputStream = editor.newOutputStream(0);  
                        if (downloadUrlToStream(imageUrl, outputStream)) {  
                            editor.commit();
                            Log.v("tag","从网络取图片 >>>" + imageUrl);
                        } else {  
                            editor.abort();  
                        }  
                    }  
                    // 缓存被写入后，再次查找key对应的缓存  
                    snapShot = mDiskLruCache.get(key);  
                }else{
//                	Log.v("tag", "从文件取图片 >>>" + imageUrl);
                }
                if (snapShot != null) {  
                    fileInputStream = (FileInputStream) snapShot.getInputStream(0);  
                    fileDescriptor = fileInputStream.getFD();  
                }  
                // 将缓存数据解析成Bitmap对象  
                Bitmap bitmap = null;  
                if (fileDescriptor != null) {  
//                    bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);  
                    bitmap = decodeSampledBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
                }  
                if (bitmap != null) {  
                    // 将Bitmap对象添加到内存缓存当中  
                    addBitmapToMemoryCache(params[0], bitmap);  
                }  
                return bitmap;  
            } catch (IOException e) {  
                e.printStackTrace();
				if(callback != null) {
					callback.onError();
				}
            } finally {  
                if (fileDescriptor == null && fileInputStream != null) {  
                    try {  
                        fileInputStream.close();  
                    } catch (IOException e) {  
                    }  
                }  
            }  
            return null;  
		}

		// Once complete, see if ImageView is still around and set bitmap.
		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				bitmap = null;
			}
			if (imageViewReference != null && bitmap != null) {
				final ImageView imageView = (ImageView) imageViewReference.get();
				final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
				if (this == bitmapWorkerTask && imageView != null) {
					if(callback != null) {
						callback.onSuccess();
					}
					imageView.setImageBitmap(bitmap);
				}
			}
		}
	}

	/**
	 * 高效图片处理，图片资源来自Resources
	 * @param res
	 * @param resId
	 * @param reqWidth
	 * @param reqHeight
	 * @return
	 */
	public static Bitmap decodeSampledBitmapFromResource(Resources res,
			int resId, int reqWidth, int reqHeight) {

		// First decode with inJustDecodeBounds=true to check dimensions
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeResource(res, resId, options);

		// Calculate inSampleSize
		options.inSampleSize = calculateInSampleSize(options, reqWidth,
				reqHeight);

		// Decode bitmap with inSampleSize set
		options.inJustDecodeBounds = false;
		return BitmapFactory.decodeResource(res, resId, options);
	}
	/**
	 * 高效图片处理，图片资源来自FileDescriptor
	 * @param fileDescriptor
	 * @param reqWidth
	 * @param reqHeight
	 * @return
	 */
	public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fileDescriptor,int reqWidth, int reqHeight){
		// First decode with inJustDecodeBounds=true to check dimensions
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
		
		// Calculate inSampleSize
		options.inSampleSize = calculateInSampleSize(options, reqWidth,reqHeight);
		// Decode bitmap with inSampleSize set
		options.inJustDecodeBounds = false;
		
		return BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
	}
	/**
	 * 高效图片处理，图片资源来自网络imageUrl
	 * @param imageUrl
	 * @param reqWidth
	 * @param reqHeight
	 * @return
	 */
	public static Bitmap decodeSampledBitmapFromStream(String imageUrl,
			int reqWidth, int reqHeight) {

		// First decode with inJustDecodeBounds=true to check dimensions
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;

		HttpURLConnection con = null;
		InputStream is = null;
		try {
			URL url = new URL(imageUrl);
			con = (HttpURLConnection) url.openConnection();
			is = con.getInputStream();
			BitmapFactory.decodeStream(is, null, options);

			// Calculate inSampleSize
			options.inSampleSize = calculateInSampleSize(options, reqWidth,reqHeight);
//			Logs.v("options.inSampleSize  :" + options.inSampleSize);
			// Decode bitmap with inSampleSize set
			options.inJustDecodeBounds = false;

			con.disconnect();
			is.close();

			con = (HttpURLConnection) url.openConnection();
			is = con.getInputStream();
			
			return BitmapFactory.decodeStream(is, null, options);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (is != null) {
					is.close();
					is = null;
				}
				if (con != null) {
					con.disconnect();
					con = null;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	public static int calculateInSampleSize(BitmapFactory.Options options,
			int reqWidth, int reqHeight) {
		// width=0,height=0 表示返回原图
		if (reqWidth == 0 && reqHeight == 0) {
			return 1;
		}

		// Raw height and width of image
		final int height = options.outHeight;
		final int width = options.outWidth;
//		Logs.v("calculateInSampleSize  height :" + height + " width :" + width);
		int inSampleSize = 1;

		if (height > reqHeight || width > reqWidth) {

			final int halfHeight = height / 2;
			final int halfWidth = width / 2;

			// Calculate the largest inSampleSize value that is a power of 2 and
			// keeps both
			// height and width larger than the requested height and width.
			while ((halfHeight / inSampleSize) > reqHeight
					&& (halfWidth / inSampleSize) > reqWidth) {
				inSampleSize *= 2;
			}
		}

		return inSampleSize;
	}
	/** 
     * 建立HTTP请求，并获取Bitmap对象。 写入指定输出流
     *  
     * @param imageUrl 
     *            图片的URL地址 
     * @return 解析后的Bitmap对象 
     */  
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {  
        HttpURLConnection urlConnection = null;  
        BufferedOutputStream out = null;  
        BufferedInputStream in = null;  
        try {  
            final URL url = new URL(urlString);  
            urlConnection = (HttpURLConnection) url.openConnection();  
            in = new BufferedInputStream(urlConnection.getInputStream(), 8 * 1024);  
            out = new BufferedOutputStream(outputStream, 8 * 1024);  
            int b;  
            while ((b = in.read()) != -1) {  
                out.write(b);  
            }  
            return true;  
        } catch (final IOException e) {  
            e.printStackTrace();  
        } finally {  
            if (urlConnection != null) {  
                urlConnection.disconnect();  
            }  
            try {  
                if (out != null) {  
                    out.close();  
                }  
                if (in != null) {  
                    in.close();  
                }  
            } catch (final IOException e) {  
                e.printStackTrace();  
            }  
        }  
        return false;  
    }  

    /**
	 * 当SD卡存在或者SD卡不可被移除的时候，就调用getExternalCacheDir()方法来获取缓存路径，
	 * 否则就调用getCacheDir() 方法来获取缓存路径。
	 * 前者获取到的就是 /sdcard/Android/data/<application package>/cache这个路径，
	 * 而后者获取到的是 /data/data/<application package>/cache 这个路径。
	 * 
	 * @param context
	 * @param uniqueName
	 * @return
	 */
	public File getDiskCacheDir(Context context, String uniqueName) {
		String cachePath;
		if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())|| !Environment.isExternalStorageRemovable()) {
			cachePath = context.getExternalCacheDir().getPath();
		} else {
			cachePath = context.getCacheDir().getPath();
		}
		return new File(cachePath + File.separator + uniqueName);
	}
	/**
	 * 获取到当前应用程序的版本号
	 * @param context
	 * @return
	 */
	public int getAppVersion(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return info.versionCode;
		} catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		return 1;
	}
	/**
	 * 将字符串进行MD5编码
	 * @param key
	 * @return
	 */
	public String hashKeyForDisk(String key) {
		String cacheKey;
		try {
			final MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(key.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(key.hashCode());
		}
		return cacheKey;
	}

	private String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}
}
