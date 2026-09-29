#!/usr/bin/env bash
# Cross-builds Debian's glibc (same version as the rootfs) with the fexdroid
# patches and the on-device path layout, in an x86_64 Debian container.
#
# Why: Android's app seccomp filter kills glibc with SIGSYS (set_robust_list,
# rseq, faccessat2, SysV IPC), and Android has no /lib, /etc, /bin/sh.
# See NOTES.md N-002, N-009, N-017.
#
# Output: build/glibc/install/  (DESTDIR tree, files under $FXD_ROOT/...)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/config.sh
OUT=$PWD/build/glibc
mkdir -p "$OUT"

docker run --rm -v "$OUT:/out" -v "$PWD/patches:/patches:ro" -v "$PWD/scripts/lib:/lib-scripts:ro" \
  -e FXD_ROOT="$FXD_ROOT" -e FXD_LIBDIR="$FXD_LIBDIR" -e FXD_LDSO="$FXD_LDSO" -e GLIBC_DEB_VERSION="$GLIBC_DEB_VERSION" \
  -e JOBS="$(nproc)" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  debian:trixie bash -euo pipefail -c '
export DEBIAN_FRONTEND=noninteractive
sed -i "s/^Types: deb$/Types: deb deb-src/" /etc/apt/sources.list.d/debian.sources
apt-get update -qq
apt-get install -y -qq --no-install-recommends dpkg-dev ca-certificates quilt python3 \
  gcc-aarch64-linux-gnu g++-aarch64-linux-gnu binutils-aarch64-linux-gnu linux-libc-dev-arm64-cross \
  make gawk bison gettext texinfo patch file >/dev/null

cd /out
if [[ ! -d glibc-2.41 ]]; then apt-get source -qq "glibc=$GLIBC_DEB_VERSION"; fi
rm -rf src && cp -a glibc-2.41 src && cd src
chmod -R u+w .

# 1) seccomp workarounds (see patches/glibc/*.patch).
for p in /patches/glibc/*.patch; do echo "applying $(basename "$p")"; patch -p1 -s < "$p"; done

# 2) Hardcoded paths that must live inside the rootfs on Android.
sed -i "s|#define\t_PATH_BSHELL\t\"/bin/sh\"|#define\t_PATH_BSHELL\t\"$FXD_ROOT/bin/sh\"|" sysdeps/unix/sysv/linux/paths.h
grep -q "$FXD_ROOT/bin/sh" sysdeps/unix/sysv/linux/paths.h || { echo "paths.h patch failed"; exit 1; }
sed -i "s|#define\t_PATH_SHELLS\t\"/etc/shells\"|#define\t_PATH_SHELLS\t\"$FXD_ROOT/etc/shells\"|; s|#define\t_PATH_TMP\t\"/tmp/\"|#define\t_PATH_TMP\t\"$FXD_ROOT/tmp/\"|; s|#define\t_PATH_VARTMP\t\"/var/tmp/\"|#define\t_PATH_VARTMP\t\"$FXD_ROOT/var/tmp/\"|" sysdeps/unix/sysv/linux/paths.h
# POSIX shm/sem (shm_open, sem_open) live in /dev/shm, which apps cannot use.
sed -i "s|#define SHMDIR _PATH_DEV \"shm/\"|#define SHMDIR \"$FXD_ROOT/dev/shm/\"|" include/shm-directory.h
grep -q "$FXD_ROOT/dev/shm/" include/shm-directory.h || { echo "shm-directory.h patch failed"; exit 1; }
# fexdroid SysV shm (patches/glibc/0002-*): where glibc finds ld.so + fxshmd to
# start the shared-memory daemon on demand (see NOTES.md N-017).
sed -i "s|#define FXSHM_LDSO   \"/lib/ld-linux-aarch64.so.1\"|#define FXSHM_LDSO   \"$FXD_LDSO\"|; s|#define FXSHM_DAEMON \"/usr/libexec/fxshmd\"|#define FXSHM_DAEMON \"$FXD_ROOT/usr/libexec/fxshmd\"|" sysdeps/unix/sysv/linux/fxshm-config.h
grep -q "$FXD_ROOT/usr/libexec/fxshmd" sysdeps/unix/sysv/linux/fxshm-config.h || { echo "fxshm-config.h patch failed"; exit 1; }
# fexdroid exec (patches/glibc/0004-*): the rootfs, its loader and FEX, for programs
# made for an ordinary Linux (see NOTES.md N-050).
sed -i "s|#define FXD_EXEC_ROOT \"/fxd-root\"|#define FXD_EXEC_ROOT \"$FXD_ROOT\"|; s|#define FXD_EXEC_LDSO \"/lib/ld-linux-aarch64.so.1\"|#define FXD_EXEC_LDSO \"$FXD_LDSO\"|; s|#define FXD_EXEC_FEX  \"/usr/bin/FEX\"|#define FXD_EXEC_FEX  \"$FXD_ROOT/usr/bin/FEX\"|" sysdeps/unix/sysv/linux/aarch64/fxd-exec-config.h
grep -q "$FXD_ROOT/usr/bin/FEX" sysdeps/unix/sysv/linux/aarch64/fxd-exec-config.h || { echo "fxd-exec-config.h patch failed"; exit 1; }
# nss_files and the resolver read /etc/{passwd,group,hosts,...}.
grep -rlE "\"/etc/" nss/nss_files resolv/*.h resolv/res_init.c 2>/dev/null | xargs -r sed -i "s|\"/etc/|\"$FXD_ROOT/etc/|g"

# Debian'"'"'s source package ships without the texinfo manual; skip it.
sed -i "s/ wctype manual po / wctype po /" Makeconfig
grep -q " wctype po " Makeconfig || { echo "Makeconfig manual patch failed"; exit 1; }

# 3) Configure + build.
rm -rf ../obj && mkdir ../obj && cd ../obj
cat > configparms <<EOP
slibdir=$FXD_LIBDIR
rtlddir=$FXD_LIBDIR
libdir=$FXD_LIBDIR
EOP
CC="aarch64-linux-gnu-gcc" CXX="aarch64-linux-gnu-g++" \
../src/configure --host=aarch64-linux-gnu --build=x86_64-linux-gnu \
  --prefix="$FXD_ROOT/usr" --sysconfdir="$FXD_ROOT/etc" --localstatedir="$FXD_ROOT/var" \
  --libdir="$FXD_LIBDIR" --libexecdir="$FXD_ROOT/usr/lib" \
  --with-headers=/usr/aarch64-linux-gnu/include --enable-kernel=4.14 \
  --enable-stack-protector=strong --enable-bind-now --disable-werror \
  --disable-profile --disable-nscd --disable-build-nscd --disable-timezone-tools \
  --without-selinux --with-pkgversion="Debian GLIBC $GLIBC_DEB_VERSION + fexdroid" >/out/configure.log 2>&1
make -j"$JOBS" >/out/make.log 2>&1 || { tail -40 /out/make.log; exit 1; }
rm -rf /out/install && make install DESTDIR=/out/install >/out/install.log 2>&1 || { tail -40 /out/install.log; exit 1; }
chown -R "$HOST_UID:$HOST_GID" /out
echo "glibc built"
'
ls -la "$OUT/install$FXD_LIBDIR/" | grep -E 'ld-linux|libc.so' 
