package app.zipper.knot.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Adds LINE sticker artwork to sticker-message notifications. */
public class StickerNotificationPreviewHook implements BaseHook {
  static final String REPOST_MARKER = "knot.sticker_notification_preview_repost";

  private static final String STICKER_BASE =
      "https://stickershop.line-scdn.net/stickershop/v1/sticker/";
  private static final int DB_ATTEMPTS = 30;
  private static final long RETRY_DELAY_MS = 50L;
  private static final int MAX_STICKER_BYTES = 2 * 1024 * 1024;
  private static final int MAX_BITMAP_DIMENSION = 768;
  private static final int MAX_CACHE_ENTRIES = 32;
  private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

  private static final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-StickerNotification");
            thread.setDaemon(true);
            return thread;
          });

  private static final Object cacheLock = new Object();
  private static final LinkedHashMap<String, CachedSticker> stickerCache =
      new LinkedHashMap<String, CachedSticker>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedSticker> eldest) {
          return size() > MAX_CACHE_ENTRIES;
        }
      };
  private static final Set<String> inFlight = new HashSet<>();

  private static final ThreadLocal<Boolean> reposting =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    Knot.module
        .hook(
            Reflect.findMethodExact(
                NotificationManager.class, "notify", String.class, int.class, Notification.class))
        .intercept(
            chain -> {
              if (!config.imageNotificationPreview.enabled || Boolean.TRUE.equals(reposting.get())) {
                return chain.proceed();
              }

              String tag = (String) chain.getArg(0);
              int id = (int) chain.getArg(1);
              Notification notification = (Notification) chain.getArg(2);
              if (!isCandidate(tag, notification)) return chain.proceed();

              LineVersion.Config version = LineVersion.get();
              if (version == null) return chain.proceed();
              String messageId =
                  stringExtra(notification.extras, version.notification.messageIdExtra);
              if (!hasText(messageId)) return chain.proceed();

              CapturedMessageStore.MessageData captured = CapturedMessageStore.get(messageId);
              String stickerId = captured == null ? null : stickerId(captured.parameter);
              if (hasText(stickerId)) {
                Bitmap cached = cachedSticker(stickerId);
                if (cached != null) {
                  Context context = Knot.currentApplication();
                  Notification enriched =
                      context == null ? null : buildPreview(context, notification, cached);
                  if (enriched != null) {
                    Knot.log(
                        "Knot: sticker preview: pre-notify cache hit ageMs="
                            + CapturedMessageStore.ageMs(captured));
                    return chain.proceed(new Object[] {tag, id, enriched});
                  }
                }

                Object result = chain.proceed();
                executor.execute(
                    () -> updateStickerNotification(tag, id, notification, messageId, stickerId));
                return result;
              }

              // A directly captured Message without STKID is authoritative enough to avoid opening
              // SQLite for every ordinary text/image/video notification.
              if (captured != null) return chain.proceed();

              Object result = chain.proceed();
              executor.execute(
                  () -> updateStickerNotification(tag, id, notification, messageId, null));
              return result;
            });
  }

  /** Starts downloading sticker artwork as soon as the decrypted Message object is available. */
  static void prefetch(String parameter) {
    String stickerId = stickerId(parameter);
    if (!hasText(stickerId) || cachedSticker(stickerId) != null) return;

    synchronized (cacheLock) {
      if (!inFlight.add(stickerId)) return;
    }

    executor.execute(
        () -> {
          try {
            Bitmap bitmap = downloadSticker(stickerId);
            if (bitmap != null) putCachedSticker(stickerId, bitmap);
          } finally {
            synchronized (cacheLock) {
              inFlight.remove(stickerId);
            }
          }
        });
  }

  private static void updateStickerNotification(
      String tag, int id, Notification original, String messageId, String knownStickerId) {
    try {
      Context context = Knot.currentApplication();
      if (context == null) return;

      String stickerId = knownStickerId;
      if (!hasText(stickerId)) stickerId = awaitStickerId(context, messageId);
      if (!hasText(stickerId)) return;

      Bitmap bitmap = cachedSticker(stickerId);
      if (bitmap == null) {
        bitmap = downloadSticker(stickerId);
        if (bitmap != null) putCachedSticker(stickerId, bitmap);
      }
      if (bitmap == null) {
        Knot.log("Knot: sticker preview: artwork unavailable");
        return;
      }

      Notification enriched = buildPreview(context, original, bitmap);
      if (enriched == null) return;
      repost(context, tag, id, enriched);
      Knot.log("Knot: sticker preview: notification updated");
    } catch (Throwable t) {
      Knot.log("Knot: sticker preview failed: " + t.getClass().getSimpleName());
    }
  }

  private static boolean isCandidate(String tag, Notification notification) {
    if (notification == null || notification.extras == null) return false;
    if (notification.extras.getBoolean(REPOST_MARKER, false)) return false;
    if (notification.extras.getBoolean(ImageNotificationPreviewHook.REPOST_MARKER, false)) {
      return false;
    }
    if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
    if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
    if (Notification.CATEGORY_CALL.equals(notification.category)
        || Notification.CATEGORY_SERVICE.equals(notification.category)) return false;

    LineVersion.Config version = LineVersion.get();
    if (version == null) return false;
    LineVersion.Config.Notification cfg = version.notification;
    return sameNonEmpty(tag, cfg.messageNotificationTag) || sameNonEmpty(tag, cfg.chatNotificationTag);
  }

  private static String awaitStickerId(Context context, String messageId) {
    for (int attempt = 0; attempt < DB_ATTEMPTS; attempt++) {
      String parameter = readParameter(context, messageId);
      if (parameter != null) {
        String stickerId = stickerId(parameter);
        if (hasText(stickerId)) return stickerId;
        if (hasText(parameter)) return null;
      }
      if (!sleepBriefly()) break;
    }
    return null;
  }

  private static String readParameter(Context context, String messageId) {
    File dbFile = context.getDatabasePath("naver_line");
    if (!dbFile.exists()) return null;

    SQLiteDatabase db = null;
    Cursor cursor = null;
    try {
      db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
      cursor =
          db.rawQuery(
              "SELECT parameter FROM chat_history WHERE server_id = ? LIMIT 1",
              new String[] {messageId});
      if (!cursor.moveToFirst()) return null;
      if (cursor.isNull(0)) return "";
      return cursor.getString(0);
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (cursor != null) cursor.close();
      if (db != null) db.close();
    }
  }

  private static String stickerId(String parameter) {
    Map<String, String> metadata = parseParameter(parameter);
    String value = firstValue(metadata, "STKID", "stickerId", "sticker_id");
    if (!hasText(value) || value.length() > 64) return null;
    return value;
  }

  private static Bitmap downloadSticker(String stickerId) {
    HttpURLConnection connection = null;
    try {
      String url = STICKER_BASE + Uri.encode(stickerId) + "/android/sticker.png";
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(1500);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("Accept", "image/*");

      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > MAX_STICKER_BYTES) return null;
          out.write(buffer, 0, read);
        }
        return decodeBytesScaled(out.toByteArray());
      }
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static Bitmap decodeBytesScaled(byte[] data) {
    if (data == null || data.length == 0) return null;
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

    int sample = 1;
    while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_BITMAP_DIMENSION) {
      sample <<= 1;
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    return BitmapFactory.decodeByteArray(data, 0, data.length, options);
  }

  private static Notification buildPreview(Context context, Notification original, Bitmap bitmap) {
    try {
      Notification.Builder builder = Notification.Builder.recoverBuilder(context, original);
      Bundle marker = new Bundle();
      marker.putBoolean(REPOST_MARKER, true);
      marker.putBoolean(ImageNotificationPreviewHook.REPOST_MARKER, true);

      // BigPictureStyle should survive StackMessageNotificationsHook, so hide the LINE message id
      // from subsequent Knot notification hooks on the enriched notification only.
      LineVersion.Config version = LineVersion.get();
      if (version != null && hasText(version.notification.messageIdExtra)) {
        marker.putString(version.notification.messageIdExtra, "");
      }

      builder.addExtras(marker);
      builder.setOnlyAlertOnce(true);
      builder.setLargeIcon(bitmap);
      builder.setStyle(
          new Notification.BigPictureStyle()
              .bigPicture(bitmap)
              .setSummaryText(original.extras.getCharSequence(Notification.EXTRA_TEXT)));
      return builder.build();
    } catch (Throwable t) {
      Knot.log("Knot: sticker preview build failed: " + t.getClass().getSimpleName());
      return null;
    }
  }

  private static void repost(Context context, String tag, int id, Notification notification) {
    NotificationManager nm =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) return;

    reposting.set(Boolean.TRUE);
    try {
      if (tag == null) {
        nm.notify(id, notification);
      } else {
        nm.notify(tag, id, notification);
      }
    } finally {
      reposting.remove();
    }
  }

  private static Bitmap cachedSticker(String stickerId) {
    if (!hasText(stickerId)) return null;
    synchronized (cacheLock) {
      pruneCacheLocked();
      CachedSticker cached = stickerCache.get(stickerId);
      return cached == null ? null : cached.bitmap;
    }
  }

  private static void putCachedSticker(String stickerId, Bitmap bitmap) {
    if (!hasText(stickerId) || bitmap == null) return;
    synchronized (cacheLock) {
      pruneCacheLocked();
      stickerCache.put(stickerId, new CachedSticker(bitmap, System.currentTimeMillis()));
    }
  }

  private static void pruneCacheLocked() {
    long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
    Iterator<Map.Entry<String, CachedSticker>> iterator = stickerCache.entrySet().iterator();
    while (iterator.hasNext()) {
      if (iterator.next().getValue().cachedAtMs < cutoff) iterator.remove();
    }
  }

  private static Map<String, String> parseParameter(String parameter) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!hasText(parameter)) return out;

    String trimmed = parameter.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JSONObject json = new JSONObject(trimmed);
        Iterator<String> it = json.keys();
        while (it.hasNext()) {
          String key = it.next();
          Object value = json.opt(key);
          out.put(key, value == null ? "" : String.valueOf(value));
        }
        return out;
      } catch (Throwable ignored) {
      }
    }

    String[] parts = parameter.split("\\t", -1);
    for (int i = 0; i + 1 < parts.length; i += 2) {
      if (!parts[i].isEmpty()) out.put(parts[i], parts[i + 1]);
    }
    return out;
  }

  private static String firstValue(Map<String, String> map, String... candidates) {
    for (String candidate : candidates) {
      for (Map.Entry<String, String> entry : map.entrySet()) {
        if (candidate.equalsIgnoreCase(entry.getKey())) return entry.getValue();
      }
    }
    return null;
  }

  private static String stringExtra(Bundle extras, String key) {
    if (extras == null || key == null) return null;
    Object value = extras.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean sameNonEmpty(String a, String b) {
    return hasText(a) && hasText(b) && a.equals(b);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static boolean sleepBriefly() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static final class CachedSticker {
    final Bitmap bitmap;
    final long cachedAtMs;

    CachedSticker(Bitmap bitmap, long cachedAtMs) {
      this.bitmap = bitmap;
      this.cachedAtMs = cachedAtMs;
    }
  }
}
