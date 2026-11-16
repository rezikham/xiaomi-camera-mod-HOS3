cmake_minimum_required(VERSION 3.22.1)

project("mounthide" C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# arm64-v8a only for this initial port; add other ABIs by appending to ANDROID_ABI
if(NOT ANDROID_ABI)
    set(ANDROID_ABI arm64-v8a CACHE STRING "Target ABI" FORCE)
endif()

add_library(mounthide SHARED
    mount_hide.c
)

target_include_directories(mounthide PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Link against the dynamic linker so we can use dl_iterate_phdr for PLT introspection.
target_link_libraries(mounthide PUBLIC android dl log)

# Hide symbols we don't export; keep only the JNI entry points.
set_target_properties(mounthide PROPERTIES
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# JNIEXPORT is already defined by <jni.h> (__attribute__((visibility("default")))),
# so we do not redefine it here (parentheses/quotes break CMake->shell quoting).
