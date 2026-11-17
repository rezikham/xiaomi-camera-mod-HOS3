cmake_minimum_required(VERSION 3.18.1)
project("original_portrait_bokeh" LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android configuration
if(ANDROID)
    set(CMAKE_ANDROID_NDK_TOOLCHAIN_VERSION clang)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")
endif()

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Source files
set(SOURCES
    bokeh_renderer.cpp
)

# Shader compilation (embed as string, no separate compilation needed)
# The shader source is embedded in bokeh_renderer.cpp as kComputeShaderSource

# Create shared library
add_library(original_portrait_bokeh SHARED ${SOURCES})

# Link libraries
target_link_libraries(original_portrait_bokeh
    android
    log
    EGL
    GLESv3
    jnigraphics
)

# Compile options
target_compile_options(original_portrait_bokeh PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -DGL_GLEXT_PROTOTYPES
)

# Android-specific settings
if(ANDROID)
    target_compile_options(original_portrait_bokeh PRIVATE
        -DANDROID
    )
endif()

# Install (for AAR packaging)
install(TARGETS original_portrait_bokeh
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)