cmake_minimum_required(VERSION 3.22.1)

project(sts2_re_native LANGUAGES C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

find_package(zstd REQUIRED CONFIG)

add_library(
    workshop_zstd
    SHARED
    workshop_zstd.c
)

add_library(
    monomod_android_libc_shim
    SHARED
    monomod_android_libc_shim.c
)

target_link_libraries(
    workshop_zstd
    PRIVATE
    zstd::libzstd.so
)
