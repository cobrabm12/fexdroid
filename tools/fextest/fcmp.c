// fcmp.c: comisd/ucomisd/ucomiss followed by jcc/setcc/cmovcc, NaN included, for patch 0009
// (the raw fcmp flags read directly instead of the AXFLAG conversion). The same output
// natively on x86-64 and under FEX means the conditions were remapped correctly.
#include <math.h>
#include <stdint.h>
#include <stdio.h>

#define BR(name, cmp, jcc)                                                         \
    static int name(double a, double b) {                                          \
        int r;                                                                     \
        __asm__ volatile(cmp " %1, %2\n\t" jcc " 1f\n\tmov %0, 0\n\tjmp 2f\n"      \
                         "1: mov %0, 1\n2:" : "=r"(r) : "x"(a), "x"(b) : "cc");    \
        return r;                                                                  \
    }
BR(ja_u, "ucomisd", "ja") BR(jbe_u, "ucomisd", "jbe") BR(jae_u, "ucomisd", "jae") BR(jb_u, "ucomisd", "jb")
BR(ja_c, "comisd", "ja") BR(jbe_c, "comisd", "jbe") BR(je_u, "ucomisd", "je") BR(jp_u, "ucomisd", "jp")
BR(jne_u, "ucomisd", "jne")

static int seta(double a, double b) { uint8_t r; __asm__ volatile("ucomisd %1, %2\n\tseta %0" : "=r"(r) : "x"(a), "x"(b) : "cc"); return r; }
static int setb(double a, double b) { uint8_t r; __asm__ volatile("ucomisd %1, %2\n\tsetb %0" : "=r"(r) : "x"(a), "x"(b) : "cc"); return r; }
static long cmova(double a, double b) { long r = 10, s = 20; __asm__ volatile("ucomisd %2, %3\n\tcmova %0, %1" : "+r"(r) : "r"(s), "x"(a), "x"(b) : "cc"); return r; }
static long cmovbe(double a, double b) { long r = 10, s = 20; __asm__ volatile("ucomisd %2, %3\n\tcmovbe %0, %1" : "+r"(r) : "r"(s), "x"(a), "x"(b) : "cc"); return r; }
static int ja_s(float a, float b) { int r; __asm__ volatile("ucomiss %1, %2\n\tja 1f\n\tmov %0, 0\n\tjmp 2f\n1: mov %0, 1\n2:" : "=r"(r) : "x"(a), "x"(b) : "cc"); return r; }
// The flags stay live after the compare: a second consumer in another block.
static int both(double a, double b) {
    int r;
    __asm__ volatile("ucomisd %1, %2\n\tjbe 1f\n\tmov %0, 1\n\tjmp 3f\n1: jp 2f\n\tmov %0, 2\n\tjmp 3f\n2: mov %0, 3\n3:"
                     : "=r"(r) : "x"(a), "x"(b) : "cc");
    return r;
}

int main(void) {
    const double v[] = {-2.5, -0.0, 0.0, 1.0, 1.0, 3.75, INFINITY, -INFINITY, NAN};
    const int n = sizeof v / sizeof v[0];
    unsigned long h = 0;
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++) {
            double a = v[i], b = v[j];
            int r[] = {ja_u(a, b), jbe_u(a, b), jae_u(a, b), jb_u(a, b), ja_c(a, b), jbe_c(a, b), je_u(a, b), jp_u(a, b),
                       jne_u(a, b), seta(a, b), setb(a, b), (int)cmova(a, b), (int)cmovbe(a, b), ja_s((float)a, (float)b), both(a, b)};
            printf("%g %g:", a, b);
            for (unsigned k = 0; k < sizeof r / sizeof r[0]; k++) { printf(" %d", r[k]); h = h * 31 + r[k]; }
            printf("\n");
        }
    printf("hash %lx\n", h);
    return 0;
}
