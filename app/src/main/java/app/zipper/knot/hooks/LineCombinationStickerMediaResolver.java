package app.zipper.knot.hooks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import app.zipper.knot.LineVersion;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

final class LineCombinationStickerMediaResolver {
  private static final long METADATA_WAIT_MS = 6000L;
  private static final int MAX_METADATA_BYTES = 1024 * 1024;
  private static final int MAX_LAYOUTS = 16;
  private static final int MAX_CANVAS_DIMENSION = 768;
  private static final int MAX_METADATA_CACHE_ENTRIES = 64;
  private static final Map<String, String> metadataMemoryCache =
      Collections.synchronizedMap(
          new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
              return size() > MAX_METADATA_CACHE_ENTRIES;
            }
          });

  private static volatile ServiceHolder serviceHolder;

  private LineCombinationStickerMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context, LineStickerGlideMediaResolver.StickerMetadata metadata) {
    if (context == null || metadata == null || !metadata.isArrangedSticker()) return null;
    try {
      String json = loadMetadata(context, metadata.combinationStickerId);
      CombinationSpec spec = parseMetadata(json);
      return spec == null ? null : compose(context, metadata.combinationStickerId, spec);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static NotificationMediaFileStore.Attachment compose(
      Context context, String combinationId, CombinationSpec spec) {
    float largest = Math.max(spec.canvasWidth, spec.canvasHeight);
    if (!(largest > 0.0f)) return null;
    float scale = Math.min(1.0f, MAX_CANVAS_DIMENSION / largest);
    int width = Math.max(1, Math.round(spec.canvasWidth * scale));
    int height = Math.max(1, Math.round(spec.canvasHeight * scale));

    Bitmap output = null;
    try {
      output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(output);
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

      for (PartSpec part : spec.parts) {
        LineStickerGlideMediaResolver.StickerMetadata sticker =
            new LineStickerGlideMediaResolver.StickerMetadata(
                part.stickerId, part.productId, part.stickerVersion, part.stickerHash, null);
        File file = LineStickerGlideMediaResolver.requestStickerFile(context, sticker);
        if (file == null || !file.isFile() || file.length() <= 0L) return null;

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) return null;
        try {
          RectF source = new RectF(0.0f, 0.0f, bitmap.getWidth(), bitmap.getHeight());
          RectF destination =
              new RectF(
                  part.x * scale,
                  part.y * scale,
                  (part.x + part.width) * scale,
                  (part.y + part.height) * scale);
          Matrix matrix = new Matrix();
          matrix.setRectToRect(source, destination, Matrix.ScaleToFit.FILL);
          if (part.rotation != 0.0f) {
            matrix.postRotate(part.rotation, destination.centerX(), destination.centerY());
          }
          canvas.drawBitmap(bitmap, matrix, paint);
        } finally {
          bitmap.recycle();
        }
      }

      return NotificationMediaFileStore.put(
          context, "combination-sticker:" + combinationId, output, "image/png");
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (output != null) output.recycle();
    }
  }

  private static String loadMetadata(Context context, String combinationId) {
    String cached = metadataMemoryCache.get(combinationId);
    if (hasText(cached)) return cached;

    File lineCache =
        new File(
            new File(new File(context.getFilesDir(), "combination_sticker"), "1"),
            combinationId + ".json");
    String local = readText(lineCache);
    if (hasText(local)) {
      metadataMemoryCache.put(combinationId, local);
      return local;
    }

    String downloaded = requestMetadataWithLine(context, combinationId);
    if (hasText(downloaded)) metadataMemoryCache.put(combinationId, downloaded);
    return downloaded;
  }

  private static String requestMetadataWithLine(Context context, String combinationId) {
    Object response = null;
    try {
      ClassLoader loader = context.getClassLoader();
      LineVersion.Config version = LineVersion.get();
      if (version == null) return null;
      LineVersion.Config.Notification config = version.notification;
      ServiceHolder holder = lineService(context, loader);
      if (holder == null || !hasText(config.combinationStickerMetadataMethod)) return null;

      Class<?> continuationClass = Class.forName("kotlin.coroutines.Continuation", false, loader);
      Method request =
          holder.apiClass.getMethod(
              config.combinationStickerMetadataMethod, String.class, continuationClass);

      Object coroutineContext = emptyCoroutineContext(continuationClass, loader);
      if (coroutineContext == null) return null;

      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Object> resumed = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Object continuation =
          Proxy.newProxyInstance(
              continuationClass.getClassLoader(),
              new Class<?>[] {continuationClass},
              (proxy, method, args) -> {
                String name = method.getName();
                if ("getContext".equals(name)) return coroutineContext;
                if ("resumeWith".equals(name)) {
                  Object value = args == null || args.length == 0 ? null : args[0];
                  Throwable error = resultFailure(value);
                  if (error != null) failure.set(error);
                  else resumed.set(value);
                  latch.countDown();
                  return null;
                }
                if ("toString".equals(name)) return "KnotCombinationStickerContinuation";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                return null;
              });

      Object immediate;
      try {
        immediate = request.invoke(holder.service, combinationId, continuation);
      } catch (InvocationTargetException e) {
        throw e.getCause() == null ? e : e.getCause();
      }

      if (!isCoroutineSuspended(immediate)) {
        response = immediate;
      } else if (latch.await(METADATA_WAIT_MS, TimeUnit.MILLISECONDS)) {
        Throwable error = failure.get();
        if (error != null) throw error;
        response = resumed.get();
      } else {
        return null;
      }

      return response == null
          ? null
          : responseString(response, config.combinationStickerResponseStringMethod);
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (response instanceof Closeable) {
        try {
          ((Closeable) response).close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static ServiceHolder lineService(Context context, ClassLoader loader) {
    ServiceHolder existing = serviceHolder;
    if (existing != null) return existing;
    synchronized (LineCombinationStickerMediaResolver.class) {
      existing = serviceHolder;
      if (existing != null) return existing;

      LineVersion.Config version = LineVersion.get();
      if (version == null) return null;
      LineVersion.Config.Notification config = version.notification;
      String className = config.combinationStickerRepositoryClass;
      if (!hasText(className)
          || !hasText(config.combinationStickerInitializeMethod)
          || !hasText(config.combinationStickerServiceField)) return null;
      try {
        Class<?> repositoryClass = Class.forName(className, false, loader);
        Object repository = repositoryClass.getDeclaredConstructor().newInstance();
        repositoryClass
            .getMethod(config.combinationStickerInitializeMethod, Context.class)
            .invoke(repository, context);
        Field serviceField =
            repositoryClass.getDeclaredField(config.combinationStickerServiceField);
        serviceField.setAccessible(true);
        Object service = serviceField.get(repository);
        if (service == null) return null;
        ServiceHolder resolved = new ServiceHolder(service, serviceField.getType());
        serviceHolder = resolved;
        return resolved;
      } catch (Throwable ignored) {
        return null;
      }
    }
  }

  private static Object emptyCoroutineContext(Class<?> continuationClass, ClassLoader loader) {
    try {
      Class<?> contextType = continuationClass.getMethod("getContext").getReturnType();
      String packageName = contextType.getPackage().getName();
      String[] candidates = {packageName + ".j", "kotlin.coroutines.EmptyCoroutineContext"};
      for (String className : candidates) {
        try {
          Class<?> candidate = Class.forName(className, false, loader);
          for (Field field : candidate.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value != null && contextType.isInstance(value)) return value;
          }
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static Throwable resultFailure(Object value) {
    if (value == null) return null;
    Class<?> type = value.getClass();
    if (!type.getName().contains("Result$Failure")) return null;
    for (Field field : type.getDeclaredFields()) {
      if (!Throwable.class.isAssignableFrom(field.getType())) continue;
      try {
        field.setAccessible(true);
        return (Throwable) field.get(value);
      } catch (Throwable ignored) {
      }
    }
    return new IllegalStateException("Coroutine failed");
  }

  private static boolean isCoroutineSuspended(Object value) {
    return value != null && "COROUTINE_SUSPENDED".equals(String.valueOf(value));
  }

  private static String responseString(Object response, String methodName) {
    if (response == null || !hasText(methodName)) return null;
    try {
      Method method = response.getClass().getMethod(methodName);
      if (method.getParameterCount() != 0 || method.getReturnType() != String.class) return null;
      return (String) method.invoke(response);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static CombinationSpec parseMetadata(String json) {
    if (!hasText(json)) return null;
    try {
      JSONObject root = new JSONObject(json);
      float canvasWidth = (float) root.optDouble("canvasWidth", 0.0d);
      float canvasHeight = (float) root.optDouble("canvasHeight", 0.0d);
      JSONArray layouts = root.optJSONArray("stickerLayouts");
      if (!(canvasWidth > 0.0f)
          || !(canvasHeight > 0.0f)
          || layouts == null
          || layouts.length() == 0
          || layouts.length() > MAX_LAYOUTS) return null;

      ArrayList<PartSpec> parts = new ArrayList<>(layouts.length());
      for (int i = 0; i < layouts.length(); i++) {
        JSONObject layout = layouts.optJSONObject(i);
        if (layout == null) return null;
        JSONObject sticker = layout.optJSONObject("stickerInfo");
        JSONObject position = layout.optJSONObject("layoutInfo");
        if (sticker == null || position == null) return null;

        long productId = flexibleLong(sticker.opt("productId"));
        long stickerId = flexibleLong(sticker.opt("stickerId"));
        long stickerVersion = flexibleLong(sticker.opt("stickerVersion"));
        if (productId <= 0L || stickerId <= 0L || stickerVersion < 0L) return null;

        float x = (float) position.optDouble("x", Float.NaN);
        float y = (float) position.optDouble("y", Float.NaN);
        float width = (float) position.optDouble("width", Float.NaN);
        float height = (float) position.optDouble("height", Float.NaN);
        float rotation = (float) position.optDouble("rotation", 0.0d);
        if (!Float.isFinite(x)
            || !Float.isFinite(y)
            || !Float.isFinite(width)
            || !Float.isFinite(height)
            || !Float.isFinite(rotation)
            || !(width > 0.0f)
            || !(height > 0.0f)) return null;

        parts.add(
            new PartSpec(
                productId,
                stickerId,
                stickerVersion,
                nullableString(sticker, "stickerHash"),
                x,
                y,
                width,
                height,
                rotation));
      }
      return new CombinationSpec(canvasWidth, canvasHeight, parts);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String readText(File file) {
    if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_METADATA_BYTES)
      return null;
    try (FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toString(StandardCharsets.UTF_8.name());
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static long flexibleLong(Object value) {
    if (value == null || value == JSONObject.NULL) return -1L;
    if (value instanceof Number) return ((Number) value).longValue();
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return -1L;
    }
  }

  private static String nullableString(JSONObject json, String key) {
    Object value = json.opt(key);
    if (value == null || value == JSONObject.NULL) return null;
    String text = String.valueOf(value);
    return hasText(text) ? text : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static final class ServiceHolder {
    final Object service;
    final Class<?> apiClass;

    ServiceHolder(Object service, Class<?> apiClass) {
      this.service = service;
      this.apiClass = apiClass;
    }
  }

  private static final class CombinationSpec {
    final float canvasWidth;
    final float canvasHeight;
    final List<PartSpec> parts;

    CombinationSpec(float canvasWidth, float canvasHeight, List<PartSpec> parts) {
      this.canvasWidth = canvasWidth;
      this.canvasHeight = canvasHeight;
      this.parts = parts;
    }
  }

  private static final class PartSpec {
    final long productId;
    final long stickerId;
    final long stickerVersion;
    final String stickerHash;
    final float x;
    final float y;
    final float width;
    final float height;
    final float rotation;

    PartSpec(
        long productId,
        long stickerId,
        long stickerVersion,
        String stickerHash,
        float x,
        float y,
        float width,
        float height,
        float rotation) {
      this.productId = productId;
      this.stickerId = stickerId;
      this.stickerVersion = stickerVersion;
      this.stickerHash = stickerHash;
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      this.rotation = rotation;
    }
  }
}
