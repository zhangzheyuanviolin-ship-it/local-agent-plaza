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

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
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
    jint thread_count) {
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
    throw_java(env, "stable-diffusion.cpp failed to load the model");
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
  gen_params.vae_tiling_params.enabled = true;
  gen_params.sample_params.sample_steps = steps > 0 ? steps : 20;
  gen_params.sample_params.guidance.txt_cfg = cfg_scale > 0.0f ? cfg_scale : 7.0f;
  gen_params.sample_params.sample_method = EULER_SAMPLE_METHOD;
  gen_params.sample_params.scheduler = DISCRETE_SCHEDULER;

  sd_image_t* images = generate_image(ctx, &gen_params);
  free_sd_ctx(ctx);

  if (images == nullptr || images[0].data == nullptr) {
    if (images != nullptr) {
      std::free(images);
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
    throw_java(env, "stable-diffusion.cpp returned invalid image data");
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(static_cast<jsize>(byte_count));
  if (result != nullptr) {
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(byte_count), reinterpret_cast<jbyte*>(images[0].data));
  }

  std::free(images[0].data);
  std::free(images);
  return result;
}
