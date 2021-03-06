package de.danoeh.antennapod.core.asynctask;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;
import com.squareup.picasso.Cache;
import com.squareup.picasso.LruCache;
import com.squareup.picasso.OkHttpDownloader;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Request;
import com.squareup.picasso.RequestHandler;
import com.squareup.picasso.Transformation;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.core.service.download.HttpDownloader;
import de.danoeh.antennapod.core.storage.DBReader;

/**
 * Provides access to Picasso instances.
 */
public class PicassoProvider {

    private static final String TAG = "PicassoProvider";

    private static final boolean DEBUG = false;

    private static ExecutorService executorService;
    private static Cache memoryCache;

    private static synchronized ExecutorService getExecutorService() {
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(3);
        }
        return executorService;
    }

    private static synchronized Cache getMemoryCache(Context context) {
        if (memoryCache == null) {
            memoryCache = new LruCache(context);
        }
        return memoryCache;
    }

    private static volatile boolean picassoSetup = false;

    public static synchronized void setupPicassoInstance(Context appContext) {
        if (picassoSetup) {
            return;
        }
        OkHttpClient client = new OkHttpClient();
        client.interceptors().add(new BasicAuthenticationInterceptor(appContext));
        Picasso picasso = new Picasso.Builder(appContext)
                .indicatorsEnabled(DEBUG)
                .loggingEnabled(DEBUG)
                .downloader(new OkHttpDownloader(client))
                .addRequestHandler(new MediaRequestHandler(appContext))
                .executor(getExecutorService())
                .memoryCache(getMemoryCache(appContext))
                .listener(new Picasso.Listener() {
                    @Override
                    public void onImageLoadFailed(Picasso picasso, Uri uri, Exception e) {
                        Log.e(TAG, "Failed to load Uri:" + uri.toString());
                        e.printStackTrace();
                    }
                })
                .build();
        Picasso.setSingletonInstance(picasso);
        picassoSetup = true;
    }

    private static class BasicAuthenticationInterceptor implements Interceptor {

        private final Context context;

        public BasicAuthenticationInterceptor(Context context) {
            this.context = context;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            com.squareup.okhttp.Request request = chain.request();
            String url = request.urlString();
            String authentication = DBReader.getImageAuthentication(context, url);

            if(TextUtils.isEmpty(authentication)) {
                Log.d(TAG, "no credentials for '" + url + "'");
                return chain.proceed(request);
            }

            // add authentication
            String[] auth = authentication.split(":");
            String credentials = HttpDownloader.encodeCredentials(auth[0], auth[1], "ISO-8859-1");
            com.squareup.okhttp.Request newRequest = request
                    .newBuilder()
                    .addHeader("Authorization", credentials)
                    .build();
            Log.d(TAG, "Basic authentication with ISO-8859-1 encoding");
            Response response = chain.proceed(newRequest);
            if (!response.isSuccessful() && response.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                credentials = HttpDownloader.encodeCredentials(auth[0], auth[1], "UTF-8");
                newRequest = request
                        .newBuilder()
                        .addHeader("Authorization", credentials)
                        .build();
                Log.d(TAG, "Basic authentication with UTF-8 encoding");
                return chain.proceed(newRequest);
            } else {
                return response;
            }
        }
    }

    private static class MediaRequestHandler extends RequestHandler {

        final Context context;

        public MediaRequestHandler(Context context) {
            super();
            this.context = context;
        }

        @Override
        public boolean canHandleRequest(Request data) {
            return StringUtils.equals(data.uri.getScheme(), PicassoImageResource.SCHEME_MEDIA);
        }

        @Override
        public Result load(Request data, int networkPolicy) throws IOException {
            Bitmap bitmap = null;
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(data.uri.getPath());
                byte[] image = mmr.getEmbeddedPicture();
                if (image != null) {
                    bitmap = decodeStreamFromByteArray(data, image);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to decode image in media file", e);
            } finally {
                if (mmr != null) {
                    mmr.release();
                }
            }

            if (bitmap == null) {
                // this should never, happen, but sometimes it does, so fallback
                // check for fallback Uri
                String fallbackParam = data.uri.getQueryParameter(PicassoImageResource.PARAM_FALLBACK);
                if (fallbackParam != null) {
                    Uri fallback = Uri.parse(fallbackParam);
                    bitmap = decodeStreamFromFile(data, fallback);
                }
            }
            return new Result(bitmap, Picasso.LoadedFrom.DISK);

        }

        /* Copied/Adapted from Picasso RequestHandler classes  */

        private Bitmap decodeStreamFromByteArray(Request data, byte[] bytes) throws IOException {

            final BitmapFactory.Options options = createBitmapOptions(data);
            final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            in.mark(0);
            if (requiresInSampleSize(options)) {
                try {
                    BitmapFactory.decodeStream(in, null, options);
                } finally {
                    in.reset();
                }
                calculateInSampleSize(data.targetWidth, data.targetHeight, options, data);
            }
            try {
                return BitmapFactory.decodeStream(in, null, options);
            } finally {
                IOUtils.closeQuietly(in);
            }
        }

        private Bitmap decodeStreamFromFile(Request data, Uri uri) throws IOException {
            ContentResolver contentResolver = context.getContentResolver();
            final BitmapFactory.Options options = createBitmapOptions(data);
            if (requiresInSampleSize(options)) {
                InputStream is = null;
                try {
                    is = contentResolver.openInputStream(uri);
                    BitmapFactory.decodeStream(is, null, options);
                } finally {
                    IOUtils.closeQuietly(is);
                }
                calculateInSampleSize(data.targetWidth, data.targetHeight, options, data);
            }
            InputStream is = contentResolver.openInputStream(uri);
            try {
                return BitmapFactory.decodeStream(is, null, options);
            } finally {
                IOUtils.closeQuietly(is);
            }
        }

        private BitmapFactory.Options createBitmapOptions(Request data) {
            final boolean justBounds = data.hasSize();
            final boolean hasConfig = data.config != null;
            BitmapFactory.Options options = null;
            if (justBounds || hasConfig) {
                options = new BitmapFactory.Options();
                options.inJustDecodeBounds = justBounds;
                if (hasConfig) {
                    options.inPreferredConfig = data.config;
                }
            }
            return options;
        }

        private static boolean requiresInSampleSize(BitmapFactory.Options options) {
            return options != null && options.inJustDecodeBounds;
        }

        private static void calculateInSampleSize(int reqWidth, int reqHeight, BitmapFactory.Options options,
                                                  Request request) {
            calculateInSampleSize(reqWidth, reqHeight, options.outWidth, options.outHeight, options,
                    request);
        }

        private static void calculateInSampleSize(int reqWidth, int reqHeight, int width, int height,
                                                  BitmapFactory.Options options, Request request) {
            int sampleSize = 1;
            if (height > reqHeight || width > reqWidth) {
                final int heightRatio;
                final int widthRatio;
                if (reqHeight == 0) {
                    sampleSize = (int) Math.floor((float) width / (float) reqWidth);
                } else if (reqWidth == 0) {
                    sampleSize = (int) Math.floor((float) height / (float) reqHeight);
                } else {
                    heightRatio = (int) Math.floor((float) height / (float) reqHeight);
                    widthRatio = (int) Math.floor((float) width / (float) reqWidth);
                    sampleSize = request.centerInside
                            ? Math.max(heightRatio, widthRatio)
                            : Math.min(heightRatio, widthRatio);
                }
            }
            options.inSampleSize = sampleSize;
            options.inJustDecodeBounds = false;
        }
    }

    public static final int BLUR_RADIUS = 1;
    public static final int BLUR_IMAGE_SIZE = 100;
    public static final String BLUR_KEY = "blur";

    public static final Transformation blurTransformation = new Transformation() {
        @Override
        public Bitmap transform(Bitmap source) {
            Bitmap result =  fastblur(source, BLUR_RADIUS);
            if (result == null) {
                // just return the original
                // for some reason we couldn't transform it.
                return source;
            }
            source.recycle();
            return result;
        }

        @Override
        public String key() {
            return BLUR_KEY;
        }
    };

    public static Bitmap fastblur(Bitmap sentBitmap, int radius) {

        // Stack Blur v1.0 from
        // http://www.quasimondo.com/StackBlurForCanvas/StackBlurDemo.html
        //
        // Java Author: Mario Klingemann <mario at quasimondo.com>
        // http://incubator.quasimondo.com
        // created Feburary 29, 2004
        // Android port : Yahel Bouaziz <yahel at kayenko.com>
        // http://www.kayenko.com
        // ported april 5th, 2012

        // This is a compromise between Gaussian Blur and Box blur
        // It creates much better looking blurs than Box Blur, but is
        // 7x faster than my Gaussian Blur implementation.
        //
        // I called it Stack Blur because this describes best how this
        // filter works internally: it creates a kind of moving stack
        // of colors whilst scanning through the image. Thereby it
        // just has to add one new block of color to the right side
        // of the stack and remove the leftmost color. The remaining
        // colors on the topmost layer of the stack are either added on
        // or reduced by one, depending on if they are on the right or
        // on the left side of the stack.
        //
        // If you are using this algorithm in your code please add
        // the following line:
        //
        // Stack Blur Algorithm by Mario Klingemann <mario@quasimondo.com>
        Bitmap.Config config = sentBitmap.getConfig();
        if (config == null) {
            // Sometimes the config can be null, in those cases
            // we don't do a transform.
            return null;
        }

        Bitmap bitmap = sentBitmap.copy(config, true);

        if (radius < 1) {
            return (null);
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int[] pix = new int[w * h];
        Log.e("pix", w + " " + h + " " + pix.length);
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int r[] = new int[wh];
        int g[] = new int[wh];
        int b[] = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int vmin[] = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int dv[] = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) {
            dv[i] = (i / divsum);
        }

        yw = yi = 0;

        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {

                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;

                sir = stack[i + radius];

                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];

                rbs = r1 - Math.abs(i);

                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += w;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                // Preserve alpha channel: ( 0xff000000 & pix[yi] )
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        Log.e("pix", w + " " + h + " " + pix.length);
        bitmap.setPixels(pix, 0, w, 0, 0, w, h);

        return (bitmap);
    }
}
