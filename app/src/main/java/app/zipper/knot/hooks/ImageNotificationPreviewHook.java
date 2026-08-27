package app.zipper.knot.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Adds an image preview to LINE image-message notifications without blocking LINE's notify() call.
 *
 * <p>The fast path consumes message fields captured immediately before LINE persists chat_history,
 * avoiding SQLite polling when attachment metadata is already available. Preview bytes are fetched
 * directly from LINE OBS when an in-process OBS token has been observed; E2EE media is decrypted in
 * memory using the captured message key material. SQLite and the exact local attachment URI remain
 * compatibility fallbacks. No access token, media key, message id, chat id, or sender id is logged.
 */
public class ImageNotificationPreviewHook implements BaseHook {
  static final String REPOST_MARKER = "knot.image_notification_preview_repost";

  private static final String OBS_BASE = "https://obs-jp.line-apps.com/";
  private static final String OBS_TOKEN_REQUEST_CLASS_26130 = "rg8.t9";
  private static final int ATTACHMENT_IMAGE = 1;
  private static final int DB_ATTEMPTS = 30;
  private static final int OBS_TOKEN_ATTEMPTS = 20;
  private static final int OBS_ATTEMPTS = 6;
  private static final int FILE_ATTEMPTS = 30;
  private static final long RETRY_DELAY_MS = 50L;
  private static final int MAX_BITMAP_DIMENSION = 1200;
  private static final int MAX_PREVIEW_BYTES = 5 * 1024 * 1024;

  private static volatile String obsAccessToken;

  private static final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-ImageNotification");
            thread.setDaemon(true);
            return thread;
          });
  private static final ThreadLocal<Boolean> reposting =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    hookObsTokenCapture(lpparam.classLoader);

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
              if (definitelyNotImage(captured)) {
                Knot.log(
                    "Knot: image preview: pre-notify cache non-image type="
                        + captured.messageType
                        + " attachment="
                        + captured.attachmentType
                        + " ageMs="
                        + CapturedMessageStore.ageMs(captured));
                return chain.proceed();
              }

              // Never delay the original LINE notification. Image lookup/download happens after it
              // is posted, but pre-persistence metadata is passed into the worker when available.
              Object result = chain.proceed();
              executor.execute(
                  () -> updateImageNotification(tag, id, notification, messageId, captured));
              return result;
            });
  }

  private static void hookObsTokenCapture(ClassLoader classLoader) {
    try {
      LineVersion.Config version = LineVersion.get();
      if (version == null || !hasText(version.thrift.talkServiceClientImplClass)) return;
      Class<?> clientClass = Reflect.findClass(version.thrift.talkServiceClientImplClass, classLoader);
      int hooks = 0;
      for (Method method : clientClass.getDeclaredMethods()) {
        if (!isObsTokenAcquisitionMethod(method)) continue;
        method.setAccessible(true);
        Knot.module
            .hook(method)
            .intercept(
                chain -> {
                  Object result = chain.proceed();
                  captureObsToken(result);
                  return result;
                });
        hooks++;
      }
      Knot.log("Knot: image preview: OBS token capture hooks=" + hooks);
    } catch (Throwable t) {
      Knot.log("Knot: image preview: OBS token capture unavailable: " + t.getClass().getSimpleName());
    }
  }

  private static boolean isObsTokenAcquisitionMethod(Method method) {
    if ("acquireEncryptedAccessToken".equals(method.getName())) return true;
    if (method.getReturnType() != String.class) return false;

    Class<?>[] parameters = method.getParameterTypes();
    return parameters.length == 1
        && OBS_TOKEN_REQUEST_CLASS_26130.equals(parameters[0].getName());
  }

  private static void captureObsToken(Object result) {
    if (result == null) return;
    if (result instanceof String) {
      storeObsTokenCandidate((String) result);
      return;
    }

    // Some generated clients wrap the RPC result. Inspect only direct String fields and never log
    // their values.
    try {
      for (Field field : result.getClass().getDeclaredFields()) {
        if (field.getType() != String.class) continue;
        field.setAccessible(true);
        Object value = field.get(result);
        if (value instanceof String) storeObsTokenCandidate((String) value);
      }
    } catch (Throwable ignored) {
    }
  }

  private static void storeObsTokenCandidate(String value) {
    if (!hasText(value)) return;
    String token = value;
    int separator = value.lastIndexOf('\u001e');
    if (separator >= 0 && separator + 1 < value.length()) token = value.substring(separator + 1);
    if (token.length() < 16 || token.indexOf(' ') >= 0 || token.indexOf('\n') >= 0) return;
    obsAccessToken = token;
    Knot.log("Knot: image preview: OBS access token captured in memory");
  }

  private static boolean isCandidate(String tag, Notification notification) {
    if (notification == null || notification.extras == null) return false;
    if (notification.extras.getBoolean(REPOST_MARKER, false)) return false;
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

  private static void updateImageNotification(
      String tag,
      int id,
      Notification original,
      String messageId,
      CapturedMessageStore.MessageData captured) {
    try {
      Context context = Knot.currentApplication();
      if (context == null) return;

      ImageRow row = fromCaptured(captured);
      if (row != null && row.attachmentType == ATTACHMENT_IMAGE) {
        Knot.log(
            "Knot: image preview: pre-notify cache hit ageMs="
                + CapturedMessageStore.ageMs(captured)
                + " parameter="
                + (hasText(row.parameter) ? "present" : "absent")
                + " localUri="
                + (hasText(row.localUri) ? "present" : "absent"));
      }

      if (row == null
          || row.attachmentType != ATTACHMENT_IMAGE
          || (!hasText(row.parameter) && !hasText(row.localUri))) {
        row = awaitImageRow(context, messageId, false);
      }
      if (row == null || row.attachmentType != ATTACHMENT_IMAGE) return;

      logProbe(row);

      Bitmap bitmap = tryObsPreview(context, messageId, row);
      if (bitmap != null) {
        repost(context, tag, id, original, bitmap);
        Knot.log("Knot: image preview: notification updated from OBS source=" + row.source);
        return;
      }

      if (!hasText(row.localUri) && "capture".equals(row.source)) {
        ImageRow persisted = awaitImageRow(context, messageId, true);
        if (persisted != null) row = persisted;
      }

      bitmap = awaitLocalBitmap(context, row.localUri);
      if (bitmap != null) {
        repost(context, tag, id, original, bitmap);
        Knot.log("Knot: image preview: notification updated from local URI source=" + row.source);
        return;
      }

      Knot.log("Knot: image preview: preview unavailable after OBS/local fallback");
    } catch (Throwable t) {
      Knot.log("Knot: image preview failed: " + t.getClass().getSimpleName());
    }
  }

  private static boolean definitelyNotImage(CapturedMessageStore.MessageData captured) {
    return captured != null
        && captured.attachmentType != Integer.MIN_VALUE
        && captured.attachmentType != ATTACHMENT_IMAGE;
  }

  private static ImageRow fromCaptured(CapturedMessageStore.MessageData captured) {
    if (captured == null) return null;
    ImageRow row = new ImageRow();
    row.localId = captured.localId;
    row.chatId = captured.chatId;
    row.localUri = captured.localUri;
    row.parameter = captured.parameter;
    row.attachmentType = captured.attachmentType;
    row.source = "capture";
    return row;
  }

  private static ImageRow awaitImageRow(Context context, String messageId, boolean requireLocalUri) {
    ImageRow lastImage = null;
    for (int attempt = 0; attempt < DB_ATTEMPTS; attempt++) {
      ImageRow row = readImageRow(context, messageId);
      if (row != null) {
        if (row.attachmentType != -1 && row.attachmentType != ATTACHMENT_IMAGE) {
          Knot.log(
              "Knot: image preview: DB row confirmed non-image attachment=" + row.attachmentType);
          return null;
        }
        if (row.attachmentType == ATTACHMENT_IMAGE) {
          lastImage = row;
          if (requireLocalUri) {
            if (hasText(row.localUri)) return row;
          } else if (hasText(row.parameter) || hasText(row.localUri)) {
            return row;
          }
        }
      }
      if (!sleepBriefly()) break;
    }
    return lastImage;
  }

  private static ImageRow readImageRow(Context context, String messageId) {
    File dbFile = context.getDatabasePath("naver_line");
    if (!dbFile.exists()) return null;

    SQLiteDatabase db = null;
    Cursor cursor = null;
    try {
      db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
      cursor =
          db.rawQuery(
              "SELECT * FROM chat_history WHERE server_id = ? LIMIT 1",
              new String[] {messageId});
      if (!cursor.moveToFirst()) return null;

      ImageRow row = new ImageRow();
      row.localId = getString(cursor, "id");
      row.chatId = getString(cursor, "chat_id");
      row.localUri = getString(cursor, "attachement_local_uri");
      row.parameter = getString(cursor, "parameter");
      row.attachmentType = getInt(cursor, "attachement_type", -1);
      row.source = "db";
      return row;
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (cursor != null) cursor.close();
      if (db != null) db.close();
    }
  }

  private static Bitmap tryObsPreview(Context context, String messageId, ImageRow row) {
    Map<String, String> metadata = parseParameter(row.parameter);
    String oid = firstValue(metadata, "OID", "oid");
    String sid = firstValue(metadata, "SID", "sid");
    String keyMaterial = firstValue(metadata, "ENC_KM", "keyMaterial", "KEY_MATERIAL");

    String token = awaitObsToken();
    if (!hasText(token)) return null;

    List<ObsCandidate> candidates = new ArrayList<>();
    if (hasText(oid) && hasText(sid)) {
      candidates.add(
          new ObsCandidate(
              OBS_BASE + "r/talk/" + pathSegment(sid) + "/" + pathSegment(oid) + "__ud-preview",
              true,
              keyMaterial));
    }
    // Plain media is addressed by the server message id.
    candidates.add(
        new ObsCandidate(
            OBS_BASE + "r/talk/m/" + pathSegment(messageId) + "/preview", false, null));

    String application = lineApplicationHeader(context);
    String talkMeta = makeTalkMeta(messageId);

    for (ObsCandidate candidate : candidates) {
      for (int attempt = 0; attempt < OBS_ATTEMPTS; attempt++) {
        try {
          byte[] data =
              downloadObs(
                  candidate.url,
                  token,
                  application,
                  candidate.privateObject ? talkMeta : null);
          if (data == null) {
            if (!sleepBriefly()) break;
            continue;
          }

          if (hasText(candidate.keyMaterial)) {
            try {
              data = decryptMedia(data, candidate.keyMaterial);
            } catch (Throwable ignored) {
              // If the payload unexpectedly is plain, BitmapFactory below still gets a chance.
            }
          }

          Bitmap bitmap = decodeBytesScaled(data);
          if (bitmap != null) return bitmap;
          break;
        } catch (Throwable ignored) {
          break;
        }
      }
    }
    return null;
  }

  private static String awaitObsToken() {
    for (int attempt = 0; attempt < OBS_TOKEN_ATTEMPTS; attempt++) {
      String token = obsAccessToken;
      if (hasText(token)) return token;
      if (!sleepBriefly()) break;
    }
    return obsAccessToken;
  }

  private static byte[] downloadObs(
      String url, String token, String application, String talkMeta) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(1500);
    connection.setReadTimeout(1500);
    connection.setRequestProperty("Accept", "*/*");
    connection.setRequestProperty("x-line-access", token);
    if (hasText(application)) connection.setRequestProperty("x-line-application", application);
    if (hasText(talkMeta)) connection.setRequestProperty("X-Talk-Meta", talkMeta);

    try {
      int status = connection.getResponseCode();
      if (status == HttpURLConnection.HTTP_ACCEPTED) return null;
      if (status != HttpURLConnection.HTTP_OK) return new byte[0];

      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > MAX_PREVIEW_BYTES) return new byte[0];
          out.write(buffer, 0, read);
        }
        return out.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  private static String lineApplicationHeader(Context context) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      String version = info.versionName;
      if (!hasText(version)) return null;
      return "ANDROID\t" + version + "\tAndroid OS\t" + Build.VERSION.RELEASE;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String makeTalkMeta(String messageId) {
    if (!hasText(messageId)) return null;
    try {
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(raw);

      // Message field 4: id (TType.STRING)
      out.writeByte(0x0B);
      out.writeShort(4);
      byte[] id = messageId.getBytes(StandardCharsets.UTF_8);
      out.writeInt(id.length);
      out.write(id);

      // Message field 27: empty LIST<STRUCT>
      out.writeByte(0x0F);
      out.writeShort(27);
      out.writeByte(0x0C);
      out.writeInt(0);
      out.writeByte(0x00); // STOP
      out.flush();

      String thrift = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP);
      JSONObject json = new JSONObject();
      json.put("message", thrift);
      return Base64.encodeToString(
          json.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] decryptMedia(byte[] input, String keyMaterialB64) throws Exception {
    if (input == null || input.length <= 32) throw new IllegalArgumentException("short media");
    byte[] keyMaterial = Base64.decode(keyMaterialB64, Base64.DEFAULT);
    byte[] derived = hkdfSha256(keyMaterial, "FileEncryption".getBytes(StandardCharsets.UTF_8), 76);
    byte[] encKey = Arrays.copyOfRange(derived, 0, 32);
    byte[] macKey = Arrays.copyOfRange(derived, 32, 64);
    byte[] iv = new byte[16];
    System.arraycopy(derived, 64, iv, 0, 12);

    int cipherLength = input.length - 32;
    byte[] ciphertext = Arrays.copyOfRange(input, 0, cipherLength);
    byte[] expectedMac = Arrays.copyOfRange(input, cipherLength, input.length);

    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(macKey, "HmacSHA256"));
    byte[] actualMac = hmac.doFinal(ciphertext);
    if (!MessageDigest.isEqual(expectedMac, actualMac)) {
      throw new SecurityException("invalid media HMAC");
    }

    Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(ciphertext);
  }

  private static byte[] hkdfSha256(byte[] ikm, byte[] info, int length) throws Exception {
    byte[] zeroSalt = new byte[32];
    Mac extract = Mac.getInstance("HmacSHA256");
    extract.init(new SecretKeySpec(zeroSalt, "HmacSHA256"));
    byte[] prk = extract.doFinal(ikm);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] previous = new byte[0];
    int counter = 1;
    while (out.size() < length) {
      Mac expand = Mac.getInstance("HmacSHA256");
      expand.init(new SecretKeySpec(prk, "HmacSHA256"));
      expand.update(previous);
      expand.update(info);
      expand.update((byte) counter);
      previous = expand.doFinal();
      int remaining = length - out.size();
      out.write(previous, 0, Math.min(previous.length, remaining));
      counter++;
    }
    return out.toByteArray();
  }

  private static Bitmap awaitLocalBitmap(Context context, String localUri) {
    if (!hasText(localUri)) return null;
    for (int attempt = 0; attempt < FILE_ATTEMPTS; attempt++) {
      Bitmap bitmap = decodeLocalBitmap(context, localUri);
      if (bitmap != null) return bitmap;
      if (!sleepBriefly()) break;
    }
    return null;
  }

  private static Bitmap decodeLocalBitmap(Context context, String value) {
    try {
      Uri uri = Uri.parse(value);
      String scheme = uri.getScheme();
      if (scheme == null || scheme.isEmpty() || "file".equalsIgnoreCase(scheme)) {
        String path = "file".equalsIgnoreCase(scheme) ? uri.getPath() : value;
        if (!hasText(path)) return null;
        File file = new File(path);
        if (!file.isFile() || file.length() <= 0) return null;
        return decodeFileScaled(file.getAbsolutePath());
      }

      if ("content".equalsIgnoreCase(scheme)) {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri)) {
          if (in == null) return null;
          Bitmap bitmap = BitmapFactory.decodeStream(in);
          return scaleDown(bitmap);
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static Bitmap decodeFileScaled(String path) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

    int sample = sampleSize(bounds.outWidth, bounds.outHeight);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    return scaleDown(BitmapFactory.decodeFile(path, options));
  }

  private static Bitmap decodeBytesScaled(byte[] data) {
    if (data == null || data.length == 0) return null;
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
    return scaleDown(BitmapFactory.decodeByteArray(data, 0, data.length, options));
  }

  private static int sampleSize(int width, int height) {
    int sample = 1;
    while (Math.max(width / sample, height / sample) > MAX_BITMAP_DIMENSION) sample <<= 1;
    return sample;
  }

  private static Bitmap scaleDown(Bitmap bitmap) {
    if (bitmap == null) return null;
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int max = Math.max(width, height);
    if (max <= MAX_BITMAP_DIMENSION) return bitmap;

    float scale = (float) MAX_BITMAP_DIMENSION / (float) max;
    int outWidth = Math.max(1, Math.round(width * scale));
    int outHeight = Math.max(1, Math.round(height * scale));
    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true);
    if (scaled != bitmap) bitmap.recycle();
    return scaled;
  }

  private static void repost(
      Context context, String tag, int id, Notification original, Bitmap bitmap) {
    try {
      Notification.Builder builder = Notification.Builder.recoverBuilder(context, original);
      Bundle marker = new Bundle();
      marker.putBoolean(REPOST_MARKER, true);

      // StackMessageNotificationsHook requires a non-empty line.message.id. Clear it only on the
      // Knot repost so the already-built BigPictureStyle is not replaced by MessagingStyle.
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

      NotificationManager nm =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;

      reposting.set(Boolean.TRUE);
      try {
        if (tag == null) {
          nm.notify(id, builder.build());
        } else {
          nm.notify(tag, id, builder.build());
        }
      } finally {
        reposting.remove();
      }
    } catch (Throwable t) {
      Knot.log("Knot: image preview repost failed: " + t.getClass().getSimpleName());
    }
  }

  private static void logProbe(ImageRow row) {
    Map<String, String> metadata = parseParameter(row.parameter);
    List<String> keys = new ArrayList<>(metadata.keySet());
    String oid = firstValue(metadata, "OID", "oid");
    String sid = firstValue(metadata, "SID", "sid");
    String keyMaterial = firstValue(metadata, "ENC_KM", "keyMaterial", "KEY_MATERIAL");

    String uriInfo = "none";
    if (hasText(row.localUri)) {
      try {
        Uri uri = Uri.parse(row.localUri);
        uriInfo = uri.getScheme() == null ? "path" : uri.getScheme();
      } catch (Throwable ignored) {
        uriInfo = "unknown";
      }
    }

    Knot.log(
        "Knot: image preview probe: source="
            + row.source
            + " attachment="
            + row.attachmentType
            + " uri="
            + uriInfo
            + " parameterKeys="
            + keys
            + " SID="
            + (hasText(sid) ? "present" : "absent")
            + " OID="
            + (hasText(oid) ? "present" : "absent")
            + " mediaKey="
            + (hasText(keyMaterial) ? "present" : "absent"));
  }

  private static Map<String, String> parseParameter(String parameter) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!hasText(parameter)) return out;

    String trimmed = parameter.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JSONObject json = new JSONObject(trimmed);
        java.util.Iterator<String> it = json.keys();
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

  private static boolean sleepBriefly() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String pathSegment(String value) {
    if (!hasText(value)) return "";
    return Uri.encode(value);
  }

  private static String getString(Cursor cursor, String column) {
    int index = cursor.getColumnIndex(column);
    if (index < 0 || cursor.isNull(index)) return null;
    return cursor.getString(index);
  }

  private static int getInt(Cursor cursor, String column, int fallback) {
    int index = cursor.getColumnIndex(column);
    if (index < 0 || cursor.isNull(index)) return fallback;
    return cursor.getInt(index);
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

  private static final class ImageRow {
    String localId;
    String chatId;
    int attachmentType = -1;
    String localUri;
    String parameter;
    String source = "unknown";
  }

  private static final class ObsCandidate {
    final String url;
    final boolean privateObject;
    final String keyMaterial;

    ObsCandidate(String url, boolean privateObject, String keyMaterial) {
      this.url = url;
      this.privateObject = privateObject;
      this.keyMaterial = keyMaterial;
    }
  }
}
