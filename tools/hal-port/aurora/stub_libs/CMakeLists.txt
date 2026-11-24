cmake_minimum_required(VERSION 3.18.1)
project("stub_libs" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# libmetatagwrapper.so - needed by mileicafilter.so and legendST.so
add_library(libmetatagwrapper SHARED libmetatagwrapper.cpp)
target_link_libraries(libmetatagwrapper log)
target_compile_options(libmetatagwrapper PRIVATE -Wall -Wextra -fvisibility=hidden)

# libcameraopt_stub.so - needed by mileicafilter.so and legendST.so
add_library(libcameraopt_stub SHARED libcameraopt_stub.cpp)
target_link_libraries(libcameraopt_stub log)
target_compile_options(libcameraopt_stub PRIVATE -Wall -Wextra -fvisibility=hidden)

# libcom.xiaomi.cam.miexifmgr.so - needed by mileicafilter.so and legendST.so
add_library(libcom_xiaomi_cam_miexifmgr SHARED libcom_xiaomi_cam_miexifmgr.cpp)
target_link_libraries(libcom_xiaomi_cam_miexifmgr log)
target_compile_options(libcom_xiaomi_cam_miexifmgr PRIVATE -Wall -Wextra -fvisibility=hidden)

install(TARGETS libmetatagwrapper libcameraopt_stub libcom_xiaomi_cam_miexifmgr
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)