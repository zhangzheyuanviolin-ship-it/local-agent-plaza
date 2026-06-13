#include <android/log.h>
#include <jni.h>
#include <stable-diffusion.h>

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

constexpr const char* kTag = "VisualCreationJNI";

void sd_log_callback(sd_log_level_t level, const char* text, void*) {
  int android_level = ANDROID_LOG_INFO;
  if (level == SD_LOG_ERROR) {
    android_level = ANDROID_LOG_ERROR;
  } else if (level == SD_LOG_WARN) {
    android_level = ANDROID_LOG_WARN;
  } else if (level == SD_LOG_DEBUG) {
    android_level = ANDROID_LOG_DEBUG;
  }
  __android_log_print(android_level, kTag, "%s", text == nullptr ? "" : text);
}

const char* safe_c_str(const std::string& value) {
  return value.empty() ? "" : value.c_str();
}

std::string read_jstring(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return "";
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return "";
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void throw_java(JNIEnv* env, const char* message) {
  jclass exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

struct ProgressCallbackData {
  JavaVM* java_vm = nullptr;
  jobject listener = nullptr;
  jmethodID on_progress = nullptr;
};

void notify_progress(ProgressCallbackData* data, int step, int steps, float seconds_per_step) {
  if (data == nullptr || data->java_vm == nullptr || data->listener == nullptr ||
      data->on_progress == nullptr) {
    return;
  }

  JNIEnv* env = nullptr;
  bool attached = false;
  if (data->java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (data->java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      return;
    }
    attached = true;
  }

  env->CallVoidMethod(data->listener, data->on_progress, step, steps, seconds_per_step);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }

  if (attached) {
    data->java_vm->DetachCurrentThread();
  }
}

void sd_progress_callback(int step, int steps, float time, void* data) {
  notify_progress(static_cast<ProgressCallbackData*>(data), step, steps, time);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_google_ai_edge_gallery_customtasks_visualcreation_NativeImageGenerationBridge_generateSd15Image(
    JNIEnv* env,
    jobject,
    jstring model_path_j,
    jstring prompt_j,
    jstring negative_prompt_j,
    jint width,
    jint height,
    jint steps,
    jfloat cfg_scale,
    jlong seed,
    jint thread_count,
    jobject progress_listener) {
  const std::string model_path = read_jstring(env, model_path_j);
  const std::string prompt = read_jstring(env, prompt_j);
  const std::string negative_prompt = read_jstring(env, negative_prompt_j);

  if (model_path.empty()) {
    throw_java(env, "Model path is empty");
    return nullptr;
  }
  if (prompt.empty()) {
    throw_java(env, "Prompt is empty");
    return nullptr;
  }

  sd_set_log_callback(sd_log_callback, nullptr);

  ProgressCallbackData progress_data;
  if (progress_listener != nullptr) {
    env->GetJavaVM(&progress_data.java_vm);
    progress_data.listener = env->NewGlobalRef(progress_listener);
    jclass listener_class = env->GetObjectClass(progress_listener);
    progress_data.on_progress = env->GetMethodID(listener_class, "onProgress", "(IIF)V");
    env->DeleteLocalRef(listener_class);
    if (progress_data.listener == nullptr || progress_data.on_progress == nullptr) {
      throw_java(env, "Native progress callback is not available");
      return nullptr;
    }
    sd_set_progress_callback(sd_progress_callback, &progress_data);
    notify_progress(&progress_data, 0, steps > 0 ? steps : 20, 0.0f);
  } else {
    sd_set_progress_callback(nullptr, nullptr);
  }

  sd_ctx_params_t ctx_params;
  sd_ctx_params_init(&ctx_params);
  ctx_params.model_path = model_path.c_str();
  ctx_params.n_threads = thread_count > 0 ? thread_count : sd_get_num_physical_cores();
  ctx_params.wtype = SD_TYPE_COUNT;
  ctx_params.rng_type = CPU_RNG;
  ctx_params.sampler_rng_type = CPU_RNG;
  ctx_params.enable_mmap = true;
  ctx_params.keep_clip_on_cpu = true;
  ctx_params.keep_vae_on_cpu = true;
  ctx_params.keep_control_net_on_cpu = true;
  ctx_params.backend = "cpu";
  ctx_params.params_backend = "cpu";

  sd_ctx_t* ctx = new_sd_ctx(&ctx_params);
  if (ctx == nullptr) {
    sd_set_progress_callback(nullptr, nullptr);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "stable-diffusion.cpp failed to load the model");
    return nullptr;
  }
  if (!sd_ctx_supports_image_generation(ctx)) {
    free_sd_ctx(ctx);
    sd_set_progress_callback(nullptr, nullptr);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "stable-diffusion.cpp loaded the model, but it does not support image generation");
    return nullptr;
  }

  sd_img_gen_params_t gen_params;
  sd_img_gen_params_init(&gen_params);
  gen_params.prompt = prompt.c_str();
  gen_params.negative_prompt = safe_c_str(negative_prompt);
  gen_params.width = width > 0 ? width : 512;
  gen_params.height = height > 0 ? height : 512;
  gen_params.seed = seed;
  gen_params.batch_count = 1;
  gen_params.vae_tiling_params.enabled = false;
  gen_params.sample_params.sample_steps = steps > 0 ? steps : 20;
  gen_params.sample_params.guidance.txt_cfg = cfg_scale > 0.0f ? cfg_scale : 7.0f;
  gen_params.sample_params.sample_method = EULER_SAMPLE_METHOD;
  gen_params.sample_params.scheduler = DISCRETE_SCHEDULER;

  sd_image_t* images = generate_image(ctx, &gen_params);
  free_sd_ctx(ctx);
  sd_set_progress_callback(nullptr, nullptr);

  if (images == nullptr || images[0].data == nullptr) {
    if (images != nullptr) {
      std::free(images);
    }
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "stable-diffusion.cpp failed to generate an image");
    return nullptr;
  }

  const size_t byte_count =
      static_cast<size_t>(images[0].width) * static_cast<size_t>(images[0].height) *
      static_cast<size_t>(images[0].channel);
  if (byte_count == 0 || images[0].channel < 3) {
    std::free(images[0].data);
    std::free(images);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "stable-diffusion.cpp returned invalid image data");
    return nullptr;
  }

  jbyteArray pixel_bytes = env->NewByteArray(static_cast<jsize>(byte_count));
  if (pixel_bytes == nullptr) {
    std::free(images[0].data);
    std::free(images);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "stable-diffusion.cpp returned image data that is too large for Java");
    return nullptr;
  }
  env->SetByteArrayRegion(
      pixel_bytes, 0, static_cast<jsize>(byte_count), reinterpret_cast<jbyte*>(images[0].data));

  jclass result_class =
      env->FindClass("com/google/ai/edge/gallery/customtasks/visualcreation/NativeImageGenerationResult");
  if (result_class == nullptr) {
    std::free(images[0].data);
    std::free(images);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    return nullptr;
  }
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(III[B)V");
  if (constructor == nullptr) {
    env->DeleteLocalRef(result_class);
    std::free(images[0].data);
    std::free(images);
    if (progress_data.listener != nullptr) {
      env->DeleteGlobalRef(progress_data.listener);
    }
    throw_java(env, "Native image result constructor is not available");
    return nullptr;
  }

  jobject result =
      env->NewObject(
          result_class,
          constructor,
          static_cast<jint>(images[0].width),
          static_cast<jint>(images[0].height),
          static_cast<jint>(images[0].channel),
          pixel_bytes);

  std::free(images[0].data);
  std::free(images);
  if (progress_data.listener != nullptr) {
    env->DeleteGlobalRef(progress_data.listener);
  }
  env->DeleteLocalRef(pixel_bytes);
  env->DeleteLocalRef(result_class);
  return result;
}
