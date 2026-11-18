cmake_minimum_required(VERSION 3.18.1)
project("hypercam_shim" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_library(libhypercam_shim SHARED shim.cpp)

target_link_libraries(libhypercam_shim
    android
    log
    dl
)

target_compile_options(libhypercam_shim PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden
)

target_compile_definitions(libhypercam_shim PRIVATE
    ANDROID
)

install(TARGETS libhypercam_shim
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)