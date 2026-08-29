package app.zipper.knot.hooks;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import app.zipper.knot.Knot;
import java.io.InputStream;

final class MessagingStyleImageScaler {
  private static final int FALLBACK_MESSAGING_IMAGE_MAX_HEIGHT_DP = 136;
  private static final int MESSAGING_IMAGE_MAX_WIDTH_DP = 300;

  private MessagingStyleImageScaler() {}

  static NotificationMediaFileStore.Attachment constrain(
      Context context, String messageId, NotificationMediaFileStore.Attachment attachment) {
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
      NotificationMediaFileStore.Attachment constrained =
          NotificationMediaFileStore.put(
              context, messageId + ":messaging-style", scaled, outputMime);
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
}
