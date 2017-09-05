package com.telly.wasp;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Debug;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.String.format;

/**
 * Helper to deal with Bitmaps, including downloading and caching.
 *
 * @author evelio
 * @author cristian
 * @version 1.1
 */
public class BitmapHelper {
    /**
     * Unique instance of this helper
     */
    private static BitmapHelper instance;
    private static final String WASP_PREFIX = "wasp";
    private static final String MUTABLE_BITMAP_PREFIX = "mutable_%d_%d";
    private static final Random RANDOM = new Random(System.currentTimeMillis());
    /**
     * On memory pool to add already loaded from file bitmaps
     * Note: will be purged on by itself in case of low memory
     */
    private final BitmapRefCache cache;
    /**
     * the hard worker
     */
    private final BitmapLoader loader;

    /**
     * Unique constructor
     * must be quick as hell
     */
    private BitmapHelper() {
        cache = new BitmapRefCache();
        loader = new BitmapLoader();
    }

    /**
     * Singleton method
     *
     * @return {@link #instance}
     */
    public static BitmapHelper getInstance() {
        if (instance == null) {
            instance = new BitmapHelper();
        }
        return instance;
    }

    /**
     * Clears current cache if any
     */
    public void clearCache() {
        if (cache != null) {
            cache.evictAll();
        }
    }

    /**
     * Deletes one or more cache files. This method runs synchronously so you
     * must put it inside a worker thread if you do not want to block the UI
     */
    public void deleteCacheFile(Context context, String... uris) {
        if (uris == null || uris.length == 0) {
            throw new IllegalStateException("Uri array is empty or null");
        }
        for (String uri : uris) {
            File file = getCacheFileFromUri(context, uri);
            if (file.exists() && !file.delete()) {
                throw new RuntimeException("Could not delete " + file);
            }
        }
    }

    /**
     * Deletes all cached files. This method runs synchronously so you must put it
     * inside a worker thread if you do not want to block the UI
     */
    public void deleteAllCachedFiles(Context context) {
        File cacheDirectory = IOUtils.getCacheDirectory(context);
        if (cacheDirectory == null || !cacheDirectory.exists()) {
            return;
        }
        String[] files = cacheDirectory.list();
        for (String fileName : files) {
            if (!fileName.startsWith(WASP_PREFIX)) {
                continue;
            }
            File file = new File(cacheDirectory, fileName);
            if (file.exists() && !file.delete()) {
                throw new RuntimeException("Could not delete " + file);
            }
        }
    }

    /**
     * @return number of files in the cache directory. This is not necessarily the number
     *         of images downloaded, nor the images in memory cache.
     */
    public int savedFilesCount(Context context) {
        File cacheDirectory = IOUtils.getCacheDirectory(context);
        if (cacheDirectory == null || !cacheDirectory.exists() || !cacheDirectory.isDirectory()) {
            return 0;
        }
        int count = 0;
        String[] files = cacheDirectory.list();
        for (String fileName : files) {
            if (fileName.startsWith(WASP_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Try to get the bitmap from cache
     *
     * @param urlFrom A valid URL pointing to a bitmap
     * @return A bitmap associated to given url if any available or will try to download it
     *         <p/>
     *         Note: in case of urlFrom parameter is null this method does nothing
     */
    public Bitmap getBitmap(String urlFrom) {
        if (isInvalidUri(urlFrom)) {
            return null;
        }
        //Lets check the cache
        BitmapRef ref = cache.get(urlFrom);
        if (ref != null) {
            return ref.getBitmap();
        }
        return null;
    }

    /**
     * Try to get the bitmap from cache
     *
     * @param context used to get the cache directory
     * @param urlFrom A valid URL pointing to a bitmap
     * @return A bitmap associated to given url if any available or will try to download it
     *         <p/>
     *         Note: in case of urlFrom parameter is null this method does nothing
     */
    public Bitmap getBitmapFromCacheDir(Context context, String urlFrom) {
        if (isInvalidUri(urlFrom)) {
            return null;
        }
        Bitmap bitmap = getBitmap(urlFrom);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            // bitmap was already cached, just return it
            return bitmap;
        }
        // bitmap is not cached, let's see if it is persisted in the cache directory
        File file = getCacheFileFromUri(context, urlFrom);
        if (file.exists()) {
            // file is there... let's try to decode it
            try {
                bitmap = BitmapUtils.loadBitmapFile(file.getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (BitmapUtils.isBitmapValid(bitmap)) {
                return putMapInCache(urlFrom, bitmap);
            }
        }
        return null;
    }

    private Bitmap putMapInCache(String cacheId, Bitmap bitmap) {
        BitmapRef bitmapRef = new BitmapRef(cacheId);
        bitmapRef.loaded(bitmap);
        cache.put(cacheId, bitmapRef);
        return bitmap;
    }

    private static String generateCacheId() {
        return format(MUTABLE_BITMAP_PREFIX, System.currentTimeMillis(), RANDOM.nextInt(Integer.MAX_VALUE));
    }

    /**
     * Safely tries to decode a drawable resource by checking the bitmap size
     * and evict last-recently used elements if necessary. This will also
     * cache the file once it is decoded, so that further calls to this
     * method will return the same bitmap reference if still in cache.
     *
     * @param res      resources instance to get the drawable contents
     * @param drawable the drawable resource to decode
     * @return a bitmap representation of the drawable resource
     */
    public Bitmap decodeResource(Resources res, final int drawable) {
        // try to get the bitmap if it's cached already
        final String id = "drawable_resource:" + drawable;
        Bitmap bitmap = getBitmap(id);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            // bitmap was already cached, just return it
            return bitmap;
        }

        // bitmap is not cached, let's decode the resource and return it
        InputStream inputStream = res.openRawResource(drawable);
        bitmap = decodeStream(inputStream, id, false);
        try {
            inputStream.close();
        } catch (Exception ignored) {
        }
        return bitmap;
    }

    /**
     * Safely tries to decode an input stream by checking the bitmap size
     * and evict last-recently used elements if necessary. This will also
     * cache the file once it is decoded, so that further calls to this
     * method will return the same bitmap reference if still in cache.
     *
     * @param inputStream the input stream pointing to the image to decode
     * @param id          the id will point to the decoded bitmap once cached
     * @return a bitmap representation of the drawable resource
     */
    public Bitmap decodeStream(InputStream inputStream, String id) {
        return decodeStream(inputStream, id, true);
    }

    private Bitmap decodeStream(InputStream inputStream, String id, boolean tryToGetFromCache) {
        if (tryToGetFromCache) {
            Bitmap bitmap = getBitmap(id);
            if (BitmapUtils.isBitmapValid(bitmap)) {
                // bitmap was already cached, just return it
                return bitmap;
            }
        }
        // let's get the file size
        int fileSize;
        try {
            fileSize = inputStream.available();
        } catch (Exception e) {
            return null;
        }

        // Images are usually in PNG  format which is actually a compressed bitmap.
        // When Android decodes the image, its size can increase up to 20 times.
        // It could be much less, but we are being pessimistic in order to avoid
        // OutOfMemory crashes.
        int tentativeNewFileSize = fileSize * 20;
        // if the current cache plus the file that is going to
        // be added surpasses the maximum cache size, we will have to evict
        // some files until we have enough space
        if (cache.size() + tentativeNewFileSize > cache.maxSize()) {
            cache.trimToSize(cache.maxSize() - tentativeNewFileSize);
        }

        // now that we have the bitmap, let's cache it right away
        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            return putMapInCache(id, bitmap);
        }
        return bitmap;
    }

    /**
     * Safely returns a mutable bitmap with the specified width and height. Its
     * initial density is as per {@link Bitmap#getDensity}.
     *
     * @param width  The width of the bitmap
     * @param height The height of the bitmap
     * @param config The bitmap config to create.
     * @throws IllegalArgumentException if the width or height are <= 0
     */
    public Bitmap createBitmap(int width, int height, Bitmap.Config config) {
        if (config == null) {
            throw new IllegalStateException("Bitmap.Config cannot be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be > 0");
        }

        // let's calculate the bitmap byte size
        int bitmapSize = calculateBitmapSize(width, height, config);

        // if the current cache plus the file that is going to
        // be added surpasses the maximum cache size, we will have to evict
        // some files until we have enough space
        if (cache.size() + bitmapSize > cache.maxSize()) {
            cache.trimToSize(cache.maxSize() - bitmapSize);
        }

        // now that we have the bitmap, let's cache it right away
        Bitmap bitmap = Bitmap.createBitmap(width, height, config);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            return putMapInCache(generateCacheId(), bitmap);
        }
        return bitmap;
    }

    /**
     * Safely returns an immutable bitmap from the source bitmap. The new bitmap may
     * be the same object as source, or a copy may have been made.  It is
     * initialized with the same density as the original bitmap.
     *
     * @param src source bitmap
     */
    public Bitmap createBitmap(Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight());
    }

    /**
     * Returns an immutable bitmap from the specified subset of the source
     * bitmap. The new bitmap may be the same object as source, or a copy may
     * have been made. It is initialized with the same density as the original
     * bitmap.
     *
     * @param source The bitmap we are subsetting
     * @param x      The x coordinate of the first pixel in source
     * @param y      The y coordinate of the first pixel in source
     * @param width  The number of pixels in each row
     * @param height The number of rows
     * @return A copy of a subset of the source bitmap or the source bitmap itself.
     */
    public Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }

    /**
     * Returns an immutable bitmap from subset of the source bitmap,
     * transformed by the optional matrix. The new bitmap may be the
     * same object as source, or a copy may have been made. It is
     * initialized with the same density as the original bitmap.
     * <p/>
     * If the source bitmap is immutable and the requested subset is the
     * same as the source bitmap itself, then the source bitmap is
     * returned and no new bitmap is created.
     *
     * @param source The bitmap we are subsetting
     * @param x      The x coordinate of the first pixel in source
     * @param y      The y coordinate of the first pixel in source
     * @param width  The number of pixels in each row
     * @param height The number of rows
     * @param m      Optional matrix to be applied to the pixels
     * @param filter true if the source should be filtered.
     *               Only applies if the matrix contains more than just
     *               translation.
     * @return A bitmap that represents the specified subset of source
     * @throws IllegalArgumentException if the x, y, width, height values are
     *                                  outside of the dimensions of the source bitmap.
     */
    public Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix m, boolean filter) {
        if (source == null) {
            throw new IllegalStateException("Bitmap source cannot be null");
        }
        int bitmapByteSize = BitmapUtils.getBitmapSize(source);

        // if the current cache plus the file that is going to
        // be added surpasses the maximum cache size, we will have to evict
        // some files until we have enough space
        if (cache.size() + bitmapByteSize > cache.maxSize()) {
            cache.trimToSize(cache.maxSize() - bitmapByteSize);
        }

        // now that we have the bitmap, let's cache it right away
        Bitmap bitmap = Bitmap.createBitmap(source, x, y, width, height, m, filter);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            return putMapInCache(generateCacheId(), bitmap);
        }
        return bitmap;
    }

    /**
     * Safely creates a new bitmap, scaled from an existing bitmap, when possible. If the
     * specified width and height are the same as the current width and height of the source
     * bitmap, the source bitmap is returned and now new bitmap is created.
     *
     * @param src       The source bitmap.
     * @param dstWidth  The new bitmap's desired width.
     * @param dstHeight The new bitmap's desired height.
     * @param filter    true if the source should be filtered.
     * @return The new scaled bitmap or the source bitmap if no scaling is required.
     */
    public Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (src == null) {
            throw new IllegalStateException("Bitmap source cannot be null");
        }
        Bitmap.Config config = src.getConfig();
        int bitmapByteSize = calculateBitmapSize(dstWidth, dstHeight, config);

        // if the current cache plus the file that is going to
        // be added surpasses the maximum cache size, we will have to evict
        // some files until we have enough space
        if (cache.size() + bitmapByteSize > cache.maxSize()) {
            cache.trimToSize(cache.maxSize() - bitmapByteSize);
        }

        // now that we have the bitmap, let's cache it right away
        Bitmap bitmap = Bitmap.createScaledBitmap(src, dstWidth, dstHeight, filter);
        if (BitmapUtils.isBitmapValid(bitmap)) {
            return putMapInCache(generateCacheId(), bitmap);
        }
        return bitmap;
    }

    private int calculateBitmapSize(int width, int height, Bitmap.Config config) {
        int bitmapSize = 0;
        switch (config) {
            case ARGB_8888:
                bitmapSize = width * height * 4;
                break;
            case ARGB_4444:
            case RGB_565:
                bitmapSize = width * height * 2;
                break;
            case ALPHA_8:
                bitmapSize = width * height * 1;
                break;
        }
        return bitmapSize;
    }

    /**
     * @return number of bitmaps currently cached
     */
    public int cacheSize() {
        return cache.cacheSize();
    }

    /**
     * Register a bitmap in the cache system.
     *
     * @param context used to get the cache directory
     * @param bitmap  the bitmap to save to cache
     * @param uri     the unique resource identifier to this cache
     * @param persist true if the bitmap should be persisted to file system
     */
    public void cacheBitmap(final Context context, final Bitmap bitmap, final String uri, boolean persist) {
        if (!BitmapUtils.isBitmapValid(bitmap)) {
            return;
        }

        if (persist) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    File file = getCacheFileFromUri(context, uri);
                    try {
                        FileOutputStream stream = new FileOutputStream(file);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                        stream.close();
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }

        BitmapRef bitmapRef = new BitmapRef(uri);
        bitmapRef.bitmapRef = bitmap;
        cache.put(uri, bitmapRef);
    }

    /**
     * Try to get a list of Bitmaps, if any of them is already on cache given observer will be
     * notified about right away, those not in cache will be loaded later and observer will get it
     *
     * @param context  Context to use
     * @param urls     List of URL to download/load from
     * @param observer Will be notified on bitmap loaded
     */
    public void bulkBitmaps(Context context, List<String> urls, BaseBitmapObserver observer) {
        if (observer == null || urls == null || urls.size() < 1) {
            return;
        }
        for (String url : urls) {
            observer.setTakeUriIntoAccount(false);// since the same observer will be used for all urls
            registerBitmapObserver(context, url, observer, null);
        }
    }

    /**
     * Download and put in cache a bitmap
     *
     * @param context  Context to use
     * @param urlFrom  A valid URL pointing to a bitmap
     * @param observer Will be notified on bitmap loaded
     */
    public void registerBitmapObserver(Context context, String urlFrom, BaseBitmapObserver observer, com.telly.wasp.BitmapLoader fileLoader) {
        if (isInvalidUri(urlFrom)) {
            return;
        }
        //Lets check the cache
        BitmapRef ref = cache.get(urlFrom);
        Bitmap bitmap = null;
        if (ref == null) {
            //Hummm nothing in cache lets try to put it in cache
            ref = new BitmapRef(urlFrom);
            cache.putAndObserve(urlFrom, ref);
        } else {
            bitmap = ref.getBitmap();
        }

        if (!BitmapUtils.isBitmapValid(bitmap)) { //humm garbage collected or not already loaded lest try to load it anyway
            ref.addObserver(observer);
            if (fileLoader != null) {
                ref.setLoader(fileLoader);
            }
            loader.load(context, ref);
        } else {
            observer.update(ref, null); // We got a valid ref and bitmap let's the observer know
        }
    }

    /**
     * Download and put in cache a bitmap
     *
     * @param context  Context to use
     * @param observer Will be notified on bitmap loaded
     */
    public void registerBitmapObserver(Context context, BaseBitmapObserver observer) {
        registerBitmapObserver(context, observer.getUrl(), observer, null);
    }

    public void registerBitmapObserver(Context context, BaseBitmapObserver observer, com.telly.wasp.BitmapLoader fileLoader) {
        registerBitmapObserver(context, observer.getUrl(), observer, fileLoader);
    }

    private static boolean isInvalidUri(String url) {
        return url == null || url.length() == 0;
    }

    private static File getCacheFileFromUri(Context context, String urlFrom) {
        File cacheDirectory = IOUtils.getCacheDirectory(context);
        return getCacheFileFromUri(cacheDirectory, urlFrom);
    }

    private static File getCacheFileFromUri(File cacheDir, String urlFrom) {
        String filename = WASP_PREFIX + String.valueOf(urlFrom.hashCode());
        return new File(cacheDir, filename);
    }

    /**
     * Wrapper to an association between an URL and a in memory cached bitmap
     * <p/>
     * URL must be immutable.
     *
     * @author evelio
     */
    static class BitmapRef extends Observable {
        Bitmap bitmapRef;
        String from;
        Observer stickyObserver;
        int currentSize;
        int previousSize;
        private com.telly.wasp.BitmapLoader mFileLoader;

        /**
         * Creates a new instance with given uri
         *
         * @param uri a bitmap url
         */
        public BitmapRef(String uri) {
            if (isInvalidUri(uri)) {
                throw new IllegalArgumentException("Invalid URL");
            }
            from = uri;
            currentSize = previousSize = 0;
        }

        /**
         * @return Bitmap cached or null if was garbage collected
         */
        public Bitmap getBitmap() {
            return bitmapRef;
        }

        /**
         * @return URL associated to this BitmapRef
         */
        public String getUri() {
            return from;
        }

        public int getCurrentSize() {
            return currentSize;
        }

        public int getPreviousSize() {
            return previousSize;
        }

        public void setLoader(com.telly.wasp.BitmapLoader fileLoader) {
            mFileLoader = fileLoader;
        }

        public com.telly.wasp.BitmapLoader getLoader() {
            return mFileLoader;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof BitmapRef) {
                BitmapRef otherRef = (BitmapRef) obj;
                return from.equals(otherRef.getUri());
            }
            return false;
        }

        /**
         * @param bmp Bitmap to associate
         */
        public void loaded(Bitmap bmp) {
            previousSize = currentSize;
            currentSize = BitmapUtils.getBitmapSize(bmp);
            bitmapRef = bmp;

            setChanged();
            notifyObservers();
            deleteObservers();
        }

        @Override
        public String toString() {
            return super.toString() + "{ "
                    + "bitmap: " + getBitmap()
                    + "from: " + from
                    + " }";
        }

        public void setStickyObserver(Observer sticky) {
            if (stickyObserver != null) {
                if (stickyObserver.equals(sticky)) {
                    return;
                } else {
                    deleteObserver(stickyObserver);
                }
            }
            stickyObserver = sticky;
            if (sticky != null) {
                addObserver(sticky);
            }
        }

        @Override
        public void deleteObservers() {
            super.deleteObservers();
            if (stickyObserver != null) {
                addObserver(stickyObserver);
            }
        }

        /**
         * Removes any reference to hard referenced bitmap and observers
         */
        public void recycle() {
            stickyObserver = null;
            deleteObservers();
            bitmapRef = null;
        }
    }

    /**
     * Calls that makes the dirty work
     *
     * @author evelio
     */
    private static class BitmapLoader {

        private final ExecutorService executor;
        /**
         * reference to those already queued
         */
        private final Set<BitmapRef> queued;
        /**
         * Directory to use as a file cache
         */
        private File cacheDir;

        /**
         * Default constructor
         */
        private BitmapLoader() {
            executor = Executors.newCachedThreadPool();
            queued = Collections.synchronizedSet(new HashSet<BitmapRef>());
        }

        /**
         * Loads a Bitmap into the given ref
         *
         * @param context context needed to get app cache directory
         * @param ref     Reference to use
         */
        private void load(Context context, BitmapRef ref) {
            if (ref == null || BitmapUtils.isBitmapValid(ref.getBitmap())) {
                return;
            }

            if (cacheDir == null) {
                cacheDir = IOUtils.getCacheDirectory(context);
            }

            if (queued.add(ref)) {
                executor.execute(new LoadTask(context, ref, cacheDir));
            }
        }


        private class LoadTask implements Runnable {
            private static final String TAG = "BitmapHelper.LoadTask";
            private final Context mContext;
            private final BitmapRef reference;
            private final File cacheDir;

            private LoadTask(Context context, BitmapRef ref, File cacheDir) {
                mContext = context;
                reference = ref;
                this.cacheDir = cacheDir;
            }

            @Override
            public void run() {
                try {
                    //load it
                    Bitmap bmp = doLoad();
                    reference.loaded(bmp);
                } catch (Exception e) {
                    if (e != null) {
                        Log.e(TAG, "Unable to load bitmap", e);
                    }
                }
                queued.remove(reference);
            }

            private Bitmap doLoad() throws IOException {
                Bitmap image = null;
                File file = BitmapHelper.getCacheFileFromUri(cacheDir, reference.from);

                if (file.exists()) {//Something is stored
                    image = BitmapUtils.loadBitmapFile(file.getCanonicalPath());
                }

                if (image == null) {//So far nothing is cached, lets download it
                    if (reference.getLoader() != null) {
                        reference.getLoader().load(mContext, reference.getUri(), file);
                    } else {
                        IOUtils.downloadFile(mContext, reference.getUri(), file);
                    }
                    if (file.exists()) {
                        image = BitmapUtils.loadBitmapFile(file.getCanonicalPath());
                    }
                }
                return image;
            }
        }
    }

    private static class BitmapRefCache extends UpdateableLruCache<String, BitmapRef> {
        private static final float DESIRED_PERCENTAGE_OF_MEMORY = 0.25f;
        private static final int BYTES_IN_A_MEGABYTE = 1048576;
        private static final int MINIMAL_MAX_SIZE = BYTES_IN_A_MEGABYTE * 4; // We want at least 4 MB
        private static final int MAX_SIZE;

        static {
            final long maxMemory = AppUtils.isHoneycombPlus() ? Runtime.getRuntime().maxMemory() : Debug.getNativeHeapSize();
            final long maxSizeLong = Math.max(MINIMAL_MAX_SIZE, (long) (maxMemory * DESIRED_PERCENTAGE_OF_MEMORY));
            // We limit it to a value as some implementations return Long.MAX_VALUE
            MAX_SIZE = (int) Math.min(((long) Integer.MAX_VALUE), Math.abs(maxSizeLong));
        }

        private final Observer cacheObserver = new Observer() {
            @Override
            public void update(Observable observable, Object data) {
                if (observable instanceof BitmapRef) {
                    updateRef((BitmapRef) observable);
                }
            }
        };

        private void updateRef(BitmapRef ref) {
            final String uri = ref.getUri();
            put(uri, ref);
        }

        public BitmapRefCache() {
            super(MAX_SIZE);
        }

        @Override
        protected int sizeOf(String key, BitmapRef value) {
            if (value != null) {
                return value.getCurrentSize();
            }
            return 0;
        }

        @Override
        protected int previousSizeOf(String key, BitmapRef value) {
            if (value != null) {
                return value.getPreviousSize();
            }
            return 0;
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, BitmapRef oldValue, BitmapRef newValue) {
            super.entryRemoved(evicted, key, oldValue, newValue);
            if (oldValue != null && !oldValue.equals(newValue)) {
                // We now just recycle the ref by removing observers and nulling the bitmap ref
                oldValue.recycle();
            }
        }

        public void putAndObserve(String urlFrom, BitmapRef ref) {
            if (urlFrom == null || ref == null) {
                return;
            }
            put(urlFrom, ref);
            ref.setStickyObserver(cacheObserver);
        }
    }
}

