package com.blxt.safety.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.StatFs;
import android.util.Log;
import android.widget.Toast;


import com.blxt.safety.bean.CacheListItem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 垃圾服务进程
 * @author 文江
 *
 */
public class CleanerService extends Service {

	 public static final String ACTION_CLEAN_AND_EXIT = "com.example.afhq.cache.cleaner.CLEAN_AND_EXIT";

	    private static final String TAG = "CleanerService";

	    private Method mGetPackageSizeInfoMethod, mFreeStorageAndNotifyMethod;
	    private OnActionListener mOnActionListener;
	    private boolean mIsScanning = false;
	    private boolean mIsCleaning = false;
	    private long mCacheSize = 0;
          /**
           * 监听回掉
           * @author 文江
           *
           */
	    public interface OnActionListener {
	    	void onScanStarted(Context context);

	    	void onScanProgressUpdated(Context context, int current, int max);

	    	void onScanCompleted(Context context, List<CacheListItem> apps);

	    	void onCleanStarted(Context context);

	    	void onCleanCompleted(Context context, long cacheSize);
	    }

	    public class CleanerServiceBinder extends Binder {

	        public CleanerService getService() {
	            return CleanerService.this;
	        }
	    }

	    private CleanerServiceBinder mBinder = new CleanerServiceBinder();

	    private class TaskScan extends AsyncTask<Void, Integer, List<CacheListItem>> {

	        private int mAppCount = 0;

	        @Override
	        protected void onPreExecute() {
	            if (mOnActionListener != null) {
	                mOnActionListener.onScanStarted(CleanerService.this);
	            }
	        }
            /**
             * 垃圾后台扫描
             */
	        @Override
	        protected List<CacheListItem> doInBackground(Void... params) {
	            mCacheSize = 0;

	            final List<ApplicationInfo> packages = getPackageManager().getInstalledApplications(
	                    PackageManager.GET_META_DATA);

	            publishProgress(0, packages.size());

	            final CountDownLatch countDownLatch = new CountDownLatch(packages.size());

	            final List<CacheListItem> apps = new ArrayList<CacheListItem>();
                     //代理获取底层垃圾
	            try {
	                for (ApplicationInfo pkg : packages) {
	                    mGetPackageSizeInfoMethod.invoke(getPackageManager(), pkg.packageName,
	                            new IPackageStatsObserver.Stub() {

	                                @Override
	                                public void onGetStatsCompleted(PackageStats pStats, boolean succeeded)
	                                        throws RemoteException {
	                                    synchronized (apps) {
	                                        publishProgress(++mAppCount, packages.size());

	                                        if (succeeded && pStats.cacheSize > 0) {
	                                            try {
	                                                apps.add(new CacheListItem(pStats.packageName,
	                                                        getPackageManager().getApplicationLabel(
	                                                                getPackageManager().getApplicationInfo(
	                                                                        pStats.packageName,
	                                                                        PackageManager.GET_META_DATA)
	                                                        ).toString(),
	                                                        getPackageManager().getApplicationIcon(
	                                                                pStats.packageName),
	                                                        pStats.cacheSize
	                                                ));

	                                                mCacheSize += pStats.cacheSize;
	                                            } catch (PackageManager.NameNotFoundException e) {
	                                                e.printStackTrace();
	                                            }
	                                        }
	                                    }

	                                    synchronized (countDownLatch) {
	                                        countDownLatch.countDown();
	                                    }
	                                }
	                            }
	                    );
	                }

	                countDownLatch.await();
	            } catch (Exception e) {
	                e.printStackTrace();
	            }

	            return new ArrayList<CacheListItem>(apps);
	        }

	        @Override
	        protected void onProgressUpdate(Integer... values) {
	            if (mOnActionListener != null) {
	                mOnActionListener.onScanProgressUpdated(CleanerService.this, values[0], values[1]);
	            }
	        }

	        @Override
	        protected void onPostExecute(List<CacheListItem> result) {
	            if (mOnActionListener != null) {
	                mOnActionListener.onScanCompleted(CleanerService.this, result);
	            }

	            mIsScanning = false;
	        }
	    }

	    private class TaskClean extends AsyncTask<Void, Void, Long> {

	        @Override
	        protected void onPreExecute() {
	            if (mOnActionListener != null) {
	                mOnActionListener.onCleanStarted(CleanerService.this);
	            }
	        }

	        @Override
	        protected Long doInBackground(Void... params) {
	        	/**
	        	 * 清除缓存
	        	 */
	            final CountDownLatch countDownLatch = new CountDownLatch(1);
	            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
	            try {
	                mFreeStorageAndNotifyMethod.invoke(getPackageManager(),
	                        (long) stat.getBlockCount() * (long) stat.getBlockSize(),
	                        new IPackageDataObserver.Stub() {
	                            @Override
	                            public void onRemoveCompleted(String packageName, boolean succeeded)
	                                    throws RemoteException {
	                                countDownLatch.countDown();
	                            }
	                        }
	                );
	                countDownLatch.await();
	            } catch (Exception e) {
	                e.printStackTrace();
	            }

	            return mCacheSize;
	        }

	        @Override
	        protected void onPostExecute(Long result) {
	            mCacheSize = 0;

	            if (mOnActionListener != null) {
	                mOnActionListener.onCleanCompleted(CleanerService.this, result);
	            }

	            mIsCleaning = false;
	        }
	    }

	    @Override
	    public IBinder onBind(Intent intent) {
	        return mBinder;
	    }

	    @Override
	    public void onCreate() {
	        try {
				PackageManager packageManager = getPackageManager();
				mGetPackageSizeInfoMethod = packageManager.getClass()
						.getMethod("getPackageSizeInfo", String.class, IPackageStatsObserver.class);

	            mFreeStorageAndNotifyMethod = packageManager.getClass().getMethod(
	                    "freeStorageAndNotify", long.class, IPackageDataObserver.class);
	        } catch (NoSuchMethodException e) {
	            e.printStackTrace();
	        }
	    }

	    @Override
	    public int onStartCommand(Intent intent, int flags, int startId) {
	        String action = intent.getAction();

	        if (action != null) {
	            if (action.equals(ACTION_CLEAN_AND_EXIT)) {
	                setOnActionListener(new OnActionListener() {
	                    @Override
	                    public void onScanStarted(Context context) {

	                    }

	                    @Override
	                    public void onScanProgressUpdated(Context context, int current, int max) {

	                    }

	                    @Override
	                    public void onScanCompleted(Context context, List<CacheListItem> apps) {
	                        if (getCacheSize() > 0) {
	                            cleanCache();
	                        }
	                    }

	                    @Override
	                    public void onCleanStarted(Context context) {

	                    }

	                    @Override
	                    public void onCleanCompleted(Context context, long cacheSize) {
	                        String msg = "清理" + cacheSize;

	                        Log.d(TAG, msg);

	                        Toast.makeText(CleanerService.this, msg, Toast.LENGTH_LONG).show();

	                        new Handler().postDelayed(new Runnable() {
	                            @Override
	                            public void run() {
	                                stopSelf();
	                            }
	                        }, 5000);
	                    }
	                });

	                scanCache();
	            }
	        }

	        return START_NOT_STICKY;
	    }

	    public void scanCache() {
	        mIsScanning = true;

	        new TaskScan().execute();
	    }

	    public void cleanCache() {
	        mIsCleaning = true;
	        new TaskClean().execute();
	    }

	    public void setOnActionListener(OnActionListener listener) {
	        mOnActionListener = listener;
	    }

	    public boolean isScanning() {
	        return mIsScanning;
	    }

	    public boolean isCleaning() {
	        return mIsCleaning;
	    }

	    public long getCacheSize() {
	        return mCacheSize;
	    }
}
