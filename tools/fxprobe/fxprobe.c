// Tiny bionic executable shipped as libfxprobe.so in nativeLibraryDir.
// The recon screen exec()s it to prove that nativeLibraryDir is executable
// for this targetSdk (the escape hatch for W^X, NOTES.md N-004).
#include <stdio.h>
#include <unistd.h>

int main(void) {
    printf("fxprobe: exec from nativeLibraryDir works (pid %d)\n", getpid());
    return 0;
}
