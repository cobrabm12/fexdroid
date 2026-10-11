// movsxloop.c [n]: n (default 100 million) sign-extending loads (movsx from memory), to count what FEX emits for them.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
volatile int16_t a[1024];
int main(int argc, char** argv) {
    long n = argc > 1 ? atol(argv[1]) : 100000000;
    for (int i = 0; i < 1024; i++) a[i] = (int16_t)(i * 77 - 30000);
    int64_t s = 0;
    for (long i = 0; i < n; i++) s += a[i & 1023];
    printf("%lld\n", (long long)s);
    return 0;
}
