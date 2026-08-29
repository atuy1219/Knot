package app.zipper.knot.hooks;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import app.zipper.knot.Knot;
import app.zipper.knot.LineVersion;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class NotificationMediaFileStore {
  private static final String DIRECTORY = "knot_notification_media";
  private static final long FILE_TTL_MS = 24L * 60L * 60L * 1000L;
  private static final int FALLBACK_MESSAGING_IMAGE_MAX_HEIGHT_DP = 136;
  private static final int MESSAGING_IMAGE_MAX_WIDTH_DP = 300;

  private static volatile Method uriForFileMethod;

  private NotificationMediaFileStore() {}

  static synchronized Attachment put(
      Context context, String messageId, Bitmap bitmap, String mimeType) {
    if (context == null || !hasText(messageId) || bitmap == null || !hasText(mimeType)) {
      return null;
    }

    try {
      File root = notificationRoot(context);
      if (root == null) return null;
      boolean png = "image/png".equalsIgnoreCase(mimeType);
      File file = new File(root, digest(messageId) + (png ? ".png" : ".jpg"));
      try (FileOutputStream out = new FileOutputStream(file, false)) {
        Bitmap.CompressFormat format = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        if (!bitmap.compress(format, png ? 100 : 88, out)) return null;
        out.flush();
      }
      return attachmentForFile(context, file, mimeType);
    } catch (Throwable t) {
      Knot.log("Knot: notification media URI unavailable: " + t.getClass().getSimpleName());
      return null;
    }
  }

  static synchronized Attachment fromExistingFile(Context context, File file, String mimeType) {
    if (context == null
        || file == null
        || !file.isFile()
        || file.length() <= 0
        || !hasText(mimeType)) {
      return null;
    }

    try {
      Attachment direct = attachmentForFile(context, file, mimeType);
      if (direct != null) return direct;
    } catch (Throwable ignored) {
    }

    try {
      File root = notificationRoot(context);
      if (root == null) return null;
      String fingerprint = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
      File staged =
          new File(root, digest("line-owned:" + fingerprint) + '.' + extensionForMime(mimeType));
      if (!staged.isFile() || staged.length() != file.length()) {
        try (FileInputStream in = new FileInputStream(file);
            FileOutputStream out = new FileOutputStream(staged, false)) {
          byte[] buffer = new byte[16 * 1024];
          int read;
          while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
          out.flush();
        }
      }
      return attachmentForFile(context, staged, mimeType);
    } catch (Throwable t) {
      Knot.log("Knot: LINE media file is not shareable: " + t.getClass().getSimpleName());
      return null;
    }
  }

  static Attachment constrainForMessagingStyle(
      Context context, String messageId, Attachment attachment) {
    if (context == null || attachment == null || attachment.uri == null) return attachment;
    if (attachment.mimeType == null || !attachment.mimeType.startsWith("image/")) return attachment;

    Bitmap decoded = null;
    Bitmap scaled = null;
    try {
      Resources systemResources = Resources.getSystem();
      float density = systemResources.getDisplayMetrics().density;
      int maxHeight = Math.round(FALLBACK_MESSAGING_IMAGE_MAX_HEIGHT_DP * Math.max(1.0f, density));
      try {
        int resourceId =
            systemResources.getIdentifier("messaging_image_max_height", "dimen", "android");
        if (resourceId != 0) maxHeight = systemResources.getDimensionPixelSize(resourceId);
      } catch (Throwable ignored) {
      }
      int maxWidth = Math.round(MESSAGING_IMAGE_MAX_WIDTH_DP * Math.max(1.0f, density));

      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      try (InputStream in = context.getContentResolver().openInputStream(attachment.uri)) {
        if (in == null) return attachment;
        BitmapFactory.decodeStream(in, null, bounds);
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return attachment;

      float targetScale =
          Math.min(
              1.0f,
              Math.min(
                  (float) maxWidth / (float) bounds.outWidth,
                  (float) maxHeight / (float) bounds.outHeight));
      if (targetScale >= 1.0f) return attachment;

      BitmapFactory.Options options = new BitmapFactory.Options();
      int sample = 1;
      while ((bounds.outWidth / (sample * 2)) >= maxWidth
          && (bounds.outHeight / (sample * 2)) >= maxHeight) {
        sample *= 2;
      }
      options.inSampleSize = Math.max(1, sample);
      try (InputStream in = context.getContentResolver().openInputStream(attachment.uri)) {
        if (in == null) return attachment;
        decoded = BitmapFactory.decodeStream(in, null, options);
      }
      if (decoded == null) return attachment;

      int width = decoded.getWidth();
      int height = decoded.getHeight();
      float scale =
          Math.min(
              1.0f, Math.min((float) maxWidth / (float) width, (float) maxHeight / (float) height));
      int outWidth = Math.max(1, Math.round(width * scale));
      int outHeight = Math.max(1, Math.round(height * scale));
      scaled =
          (outWidth == width && outHeight == height)
              ? decoded
              : Bitmap.createScaledBitmap(decoded, outWidth, outHeight, true);

      String outputMime =
          "image/png".equalsIgnoreCase(attachment.mimeType) ? "image/png" : "image/jpeg";
      Attachment constrained = put(context, messageId + ":messaging-style", scaled, outputMime);
      return constrained != null ? constrained : attachment;
    } catch (Throwable t) {
      Knot.log("Knot: MessagingStyle image constrain failed: " + t.getClass().getSimpleName());
      return attachment;
    } finally {
      if (scaled != null && scaled != decoded) {
        try {
          scaled.recycle();
        } catch (Throwable ignored) {
        }
      }
      if (decoded != null) {
        try {
          decoded.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static File notificationRoot(Context context) {
    File root = new File(new File(context.getCacheDir(), "external_share"), DIRECTORY);
    if (!root.isDirectory() && !root.mkdirs()) return null;
    prune(root);
    return root;
  }

  private static Attachment attachmentForFile(Context context, File file, String mimeType)
      throws Exception {
    Uri uri = uriForFile(context, file);
    if (uri == null) return null;
    try {
      context.grantUriPermission(
          "com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Throwable ignored) {
    }
    return new Attachment(mimeType, uri);
  }

  private static Uri uriForFile(Context context, File file) throws Exception {
    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;
    if (!hasText(config.fileProviderHelperClass) || !hasText(config.fileProviderUriMethod)) {
      return null;
    }
    Method method = uriForFileMethod;
    if (method == null) {
      Class<?> helper =
          Class.forName(config.fileProviderHelperClass, false, context.getClassLoader());
      method = helper.getDeclaredMethod(config.fileProviderUriMethod, Context.class, File.class);
      method.setAccessible(true);
      uriForFileMethod = method;
    }
    Object result = method.invoke(null, context, file);
    return result instanceof Uri ? (Uri) result : null;
  }

  private static void prune(File root) {
    File[] files = root.listFiles();
    if (files == null) return;
    long cutoff = System.currentTimeMillis() - FILE_TTL_MS;
    for (File file : files) {
      if (file.isFile() && file.lastModified() < cutoff) file.delete();
    }
  }

  private static String extensionForMime(String mimeType) {
    String value = mimeType.toLowerCase(Locale.ROOT);
    if ("image/jpeg".equals(value)) return "jpg";
    if ("image/png".equals(value)) return "png";
    if ("image/gif".equals(value)) return "gif";
    if ("image/webp".equals(value)) return "webp";
    if ("image/avif".equals(value)) return "avif";
    if ("image/heif".equals(value) || "image/heic".equals(value)) return "heic";
    return "img";
  }

  private static String digest(String value) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      out.append(Character.forDigit((hash[i] >>> 4) & 0x0F, 16));
      out.append(Character.forDigit(hash[i] & 0x0F, 16));
    }
    return out.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class Attachment {
    final String mimeType;
    final Uri uri;

    Attachment(String mimeType, Uri uri) {
      this.mimeType = mimeType;
      this.uri = uri;
    }
  }
}
