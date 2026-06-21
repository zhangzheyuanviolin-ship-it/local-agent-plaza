cmake_minimum_required(VERSION 3.22.1)

project(visual_creation_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/stable-diffusion.cpp stable-diffusion-build EXCLUDE_FROM_ALL)

add_library(visual_creation_jni SHARED visual_creation_jni.cpp)

target_include_directories(
  visual_creation_jni
  PRIVATE third_party/stable-diffusion.cpp/include
)

target_link_libraries(
  visual_creation_jni
  PRIVATE stable-diffusion android log
)
