package app.zipper.knot.hooks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import app.zipper.knot.LineVersion;
import app.zipper.knot.Reflect;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class LineSticonMediaResolver {
  private static final int DEFAULT_IMAGE_SIZE = 180;
  private static final int MAX_IMAGE_SIZE = 512;

  private LineSticonMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context, NotificationMediaCaptureStore.MessageData captured) {
    if (context == null || captured == null) return null;
    SticonSpec spec = parseSingleSticon(captured.text, captured.parameter);
    if (spec == null) return null;

    Drawable drawable = requestDrawableWithLine(context, spec);
    if (drawable == null) return null;
    Bitmap bitmap = renderDrawable(drawable);
    if (bitmap == null) return null;
    try {
      return NotificationMediaFileStore.put(
          context,
          "sticon:" + spec.productId + ":" + spec.sticonId + ":" + spec.version,
          bitmap,
          "image/png");
    } finally {
      bitmap.recycle();
    }
  }

  private static Drawable requestDrawableWithLine(Context context, SticonSpec spec) {
    try {
      LineVersion.Config version = LineVersion.get();
      if (version == null) return null;
      LineVersion.Config.Notification config = version.notification;
      if (!hasText(config.sticonImageRepositoryClass)
          || !hasText(config.sticonImageRepositoryFactoryField)
          || !hasText(config.sticonImageRepositoryFactoryMethod)
          || !hasText(config.sticonImageRepositoryCacheMethod)
          || !hasText(config.sticonImageRepositoryBatchMethod)
          || !hasText(config.sticonObservableBlockingFirstMethod)
          || !hasText(config.sticonImageKeyClass)
          || !hasText(config.sticonPaidProductClass)
          || !hasText(config.sticonPaidClass)
          || !hasText(config.sticonOptionTypeClass)) return null;

      ClassLoader loader = context.getClassLoader();
      Class<?> repositoryClass = Reflect.findClass(config.sticonImageRepositoryClass, loader);
      Object factory =
          Reflect.getStaticObjectField(repositoryClass, config.sticonImageRepositoryFactoryField);
      if (factory == null) return null;
      Object repository =
          Reflect.callMethod(factory, config.sticonImageRepositoryFactoryMethod, context);
      if (repository == null) return null;

      Class<?> keyClass = Reflect.findClass(config.sticonImageKeyClass, loader);
      Object key = createImageKey(loader, config, spec, keyClass);
      if (key == null) return null;

      Drawable cached =
          asDrawable(Reflect.callMethod(repository, config.sticonImageRepositoryCacheMethod, key));
      if (cached != null) return cached;

      AbstractCollection<Object> batchKeys = new ArrayList<>(1);
      batchKeys.add(key);
      Method batchMethod =
          Reflect.findMethodExact(
              repositoryClass, config.sticonImageRepositoryBatchMethod, AbstractCollection.class);
      Object stream = batchMethod.invoke(repository, batchKeys);
      if (stream == null) return null;
      Reflect.callMethod(stream, config.sticonObservableBlockingFirstMethod);
      return asDrawable(
          Reflect.callMethod(repository, config.sticonImageRepositoryCacheMethod, key));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Object createImageKey(
      ClassLoader loader,
      LineVersion.Config.Notification config,
      SticonSpec spec,
      Class<?> keyClass)
      throws Exception {
    Class<?> paidProductClass = Reflect.findClass(config.sticonPaidProductClass, loader);
    Object paidProduct = Reflect.newInstance(paidProductClass, spec.productId);

    Class<?> paidClass = Reflect.findClass(config.sticonPaidClass, loader);
    Object paid = Reflect.newInstance(paidClass, paidProduct, spec.sticonId);

    Class<?> optionTypeClass = Reflect.findClass(config.sticonOptionTypeClass, loader);
    String optionName =
        spec.resourceType.toUpperCase(Locale.ROOT).contains("ANIMATION") ? "ANIMATION" : "STATIC";
    Object optionType = Reflect.getStaticObjectField(optionTypeClass, optionName);
    if (optionType == null) return null;

    Class<?> sticonBaseClass = paidClass.getSuperclass();
    if (sticonBaseClass == null) return null;
    return Reflect.findConstructorExact(
            keyClass, sticonBaseClass, int.class, optionTypeClass, boolean.class)
        .newInstance(paid, spec.version, optionType, false);
  }

  private static Drawable asDrawable(Object value) {
    return value instanceof Drawable ? (Drawable) value : null;
  }

  private static Bitmap renderDrawable(Drawable source) {
    int width = normalizedDimension(source.getIntrinsicWidth());
    int height = normalizedDimension(source.getIntrinsicHeight());
    Bitmap bitmap = null;
    Rect oldBounds = new Rect(source.getBounds());
    try {
      bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      synchronized (source) {
        source.setBounds(0, 0, width, height);
        source.draw(canvas);
        source.setBounds(oldBounds);
      }
      return bitmap;
    } catch (Throwable ignored) {
      if (bitmap != null) bitmap.recycle();
      return null;
    } finally {
      try {
        source.setBounds(oldBounds);
      } catch (Throwable ignored) {
      }
    }
  }

  private static int normalizedDimension(int value) {
    if (value <= 0) return DEFAULT_IMAGE_SIZE;
    return Math.min(value, MAX_IMAGE_SIZE);
  }

  private static SticonSpec parseSingleSticon(String text, String parameter) {
    if (!hasText(text) || !hasText(parameter)) return null;
    try {
      Object replaceValue = new JSONObject(parameter).opt("REPLACE");
      if (replaceValue == null || replaceValue == JSONObject.NULL) return null;
      String replaceJson = String.valueOf(replaceValue);
      if (!hasText(replaceJson)) return null;

      JSONObject sticon = new JSONObject(replaceJson).optJSONObject("sticon");
      if (sticon == null) return null;
      JSONArray resources = sticon.optJSONArray("resources");
      if (resources == null || resources.length() != 1) return null;
      JSONObject resource = resources.optJSONObject(0);
      if (resource == null
          || resource.optInt("S", -1) != 0
          || resource.optInt("E", -1) != text.length()) return null;

      String productId = resource.optString("productId", null);
      String sticonId = resource.optString("sticonId", null);
      if (!hasText(productId) || !hasText(sticonId)) return null;
      return new SticonSpec(
          productId,
          sticonId,
          resource.optInt("version", 0),
          resource.optString("resourceType", "STATIC"));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static final class SticonSpec {
    final String productId;
    final String sticonId;
    final int version;
    final String resourceType;

    SticonSpec(String productId, String sticonId, int version, String resourceType) {
      this.productId = productId;
      this.sticonId = sticonId;
      this.version = version;
      this.resourceType = resourceType;
    }
  }
}
