package com.soundhunt.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CoverCache {
    private static final CoverCache INSTANCE = new CoverCache();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    public static CoverCache get() {
        return INSTANCE;
    }

    public void load(ImageView view, String url) {
        view.setImageBitmap(null);
        view.setTag(url);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap == null) return;
            cache.put(url, bitmap);
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    public void loadAsync(String url, Callback callback) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            callback.onBitmap(cached);
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap != null) {
                cache.put(url, bitmap);
                main.post(() -> callback.onBitmap(bitmap));
            }
        });
    }

    private Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeStream(c.getInputStream(), null, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public interface Callback {
        void onBitmap(Bitmap bitmap);
    }
}
