# Shared build configuration, sourced by the build-*.sh scripts.
# The on-device rootfs path is baked into glibc and every ELF interpreter, so it
# must match the app's package name (legacy flavor, see app/build.gradle.kts).
FXD_PACKAGE="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
FXD_ROOT="/data/data/${FXD_PACKAGE}/files/rootfs"
FXD_MULTIARCH="aarch64-linux-gnu"
FXD_LIBDIR="${FXD_ROOT}/usr/lib/${FXD_MULTIARCH}"
FXD_LDSO="${FXD_LIBDIR}/ld-linux-aarch64.so.1"
GLIBC_DEB_VERSION="2.41-12+deb13u4"   # must match libc6 in build/rootfs/rootfs-arm64.packages.txt
