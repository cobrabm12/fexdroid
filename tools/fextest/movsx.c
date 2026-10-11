// movsx.c: every movsx/movsxd form that reads memory, for patch 0008 (sign-extending loads).
// The same output natively on x86-64 and under FEX means the loads extend correctly.
#include <stdint.h>
#include <stdio.h>

static uint8_t buf[0x12000] __attribute__((aligned(16)));
#define MID 0x9000  /* the pointer handed to the loads: offsets reach both ways */
static uint64_t sum;

#define T(name, insn, dst, mem)                                  \
    static uint64_t name(const uint8_t* p, long i) {            \
        uint64_t r = 0x5555555555555555ull;                     \
        __asm__ volatile(insn : "+r"(r) : "r"(p), "r"(i) : "memory"); \
        return r;                                               \
    }
// base + displacement (positive, negative), base + index*scale + disp
T(b32,  "movsx %k0, byte ptr [%1 + 3]", , )
T(b64,  "movsx %q0, byte ptr [%1 + %2*1 - 1]", , )
T(w32,  "movsx %k0, word ptr [%1 + %2*2 + 6]", , )
T(w64,  "movsx %q0, word ptr [%1 + 10]", , )
T(w16,  "movsx %w0, byte ptr [%1 + %2*4]", , )
T(d64,  "movsxd %q0, dword ptr [%1 + %2*8 + 4]", , )
T(d64b, "movsxd %q0, dword ptr [%1 - 4 + 8]", , )
// Offsets an 8-byte ldr can encode but a 1/2/4-byte ldrs* cannot (patch 0008's first version
// checked them against the result size): multiples of 8 above 4095 / 8190 / 16380, and
// negative ones below -256.
T(bbig,  "movsx %k0, byte ptr [%1 + 0x1ff8]", , )
T(wbig,  "movsx %q0, word ptr [%1 + 0x3ff8]", , )
T(dbig,  "movsxd %q0, dword ptr [%1 + 0x7ff8]", , )
T(wneg,  "movsx %k0, word ptr [%1 - 0x2000]", , )
T(bneg,  "movsx %q0, byte ptr [%1 - 0x1001]", , )
T(dodd,  "movsxd %q0, dword ptr [%1 + 0x1236]", , )
int16_t gw __attribute__((used)) = -12345;
int32_t gd __attribute__((used)) = -2000000000;
static uint64_t rip16(void) { uint64_t r = 0x5555555555555555ull; __asm__ volatile("movsx %k0, word ptr [rip + gw]" : "+r"(r)); return r; }
static uint64_t rip32(void) { uint64_t r = 0x5555555555555555ull; __asm__ volatile("movsxd %q0, dword ptr [rip + gd]" : "+r"(r)); return r; }

int main(void) {
    for (int round = 0; round < 3; round++) {
        for (int k = 0; k < (int)sizeof buf; k++) buf[k] = (uint8_t)(k * 37 + round * 101 + (k & 1 ? 0x80 : 0));
        const uint8_t* m = buf + MID;
        for (long i = 0; i < 4; i++) {
            uint64_t v[] = {b32(buf, i), b64(buf, i + 1), w32(buf, i), w64(buf, i), w16(buf, i),
                            d64(buf, i % 2), d64b(buf, i), bbig(m + i, 0), wbig(m + i, 0), dbig(m + i, 0),
                            wneg(m + i, 0), bneg(m + i, 0), dodd(m + i, 0)};
            for (unsigned j = 0; j < sizeof v / sizeof v[0]; j++) {
                printf("%d %ld %u %016llx\n", round, i, j, (unsigned long long)v[j]);
                sum += v[j] * (j + 1);
            }
        }
    }
    printf("rip %016llx %016llx\n", (unsigned long long)rip16(), (unsigned long long)rip32());
    printf("sum %016llx\n", (unsigned long long)sum);
    return 0;
}
