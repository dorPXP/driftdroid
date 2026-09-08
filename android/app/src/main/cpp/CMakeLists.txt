cmake_minimum_required(VERSION 3.22)
project(driftdroid_android_native)

# Placeholder libwii.so target for the P1 toolchain de-risking step.
# The real runtime (runtime/ + aurora-main) gets wired in here during P3/P4
# via add_subdirectory, replacing this hello-world stub.

add_library(wii SHARED
    hello.cpp
)

target_compile_features(wii PRIVATE cxx_std_20)

find_library(log-lib log)
target_link_libraries(wii PRIVATE ${log-lib})
