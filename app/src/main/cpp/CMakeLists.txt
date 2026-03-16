cmake_minimum_required(VERSION 3.22.1)
project("openslmserver")

# llama.cpp options
set(GGML_VULKAN OFF CACHE BOOL "vulkan" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "build server" FORCE)

# Add llama.cpp as a subdirectory
add_subdirectory(llama.cpp)

# Main JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp)

# Include paths
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
)

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    llama
    ggml
    android
    log
)
