cmake_minimum_required(VERSION 3.22)
project(fexdroid C)

set(CMAKE_C_STANDARD 17)
add_compile_options(-Wall -Wextra -O2)

# JNI library: device probes for the recon screen.
add_library(fxrecon SHARED recon_jni.c probes.c vulkan_probe.c)
target_link_libraries(fxrecon log dl)

# JNI library: Xvfb framebuffer (fxshmd/memfd) -> Android Surface.
add_library(fxdisplay SHARED display_bridge.c)
target_link_libraries(fxdisplay android log)

# JNI library: phase 4 input (XTEST client) and audio (PulseAudio FIFO -> AAudio).
add_library(fxio SHARED x11_input.c audio_bridge.c sched.c)
target_link_libraries(fxio aaudio log)
