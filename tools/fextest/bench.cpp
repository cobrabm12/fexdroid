// A piece of work like a game's: virtual calls, containers, strings, float math, shared pointers.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>
#include <time.h>
struct Shape { virtual ~Shape() {} virtual double area() const = 0; virtual void step(float dt) = 0; };
struct Circle : Shape { float r; Circle(float r) : r(r) {} double area() const override { return 3.14159265 * r * r; } void step(float dt) override { r += dt * 0.01f; } };
struct Rect : Shape { float w, h; Rect(float w, float h) : w(w), h(h) {} double area() const override { return w * h; } void step(float dt) override { w += dt; h -= dt * 0.5f; } };
struct Tri : Shape { float b, h; Tri(float b, float h) : b(b), h(h) {} double area() const override { return 0.5 * b * h; } void step(float dt) override { b *= 1.0f + dt * 0.001f; } };
struct Particle { float x, y, z, vx, vy, vz, life; int kind; };
static uint32_t rng = 12345;
static inline uint32_t rnd() { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng; }
__attribute__((noinline)) static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
__attribute__((noinline)) static uint64_t fib(unsigned n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
__attribute__((noinline)) static float length3(float x, float y, float z) { return std::sqrt(x * x + y * y + z * z); }
int main(int argc, char **argv) {
    int rounds = argc > 1 ? atoi(argv[1]) : 20;
    if (argc > 2) { struct timespec t = {atoi(argv[2]), 0}; nanosleep(&t, nullptr); } // time to attach a profiler
    std::vector<std::unique_ptr<Shape>> shapes;
    for (int i = 0; i < 20000; i++) {
        switch (rnd() % 3) {
        case 0: shapes.emplace_back(new Circle(1 + rnd() % 10)); break;
        case 1: shapes.emplace_back(new Rect(1 + rnd() % 10, 1 + rnd() % 7)); break;
        default: shapes.emplace_back(new Tri(1 + rnd() % 10, 1 + rnd() % 5));
        }
    }
    std::vector<Particle> ps(30000);
    for (auto &p : ps) p = {float(rnd() % 100), float(rnd() % 100), float(rnd() % 100), float(rnd() % 7) - 3, float(rnd() % 7) - 3, float(rnd() % 7) - 3, float(rnd() % 50), int(rnd() % 4)};
    std::map<std::string, int> names;
    std::unordered_map<uint64_t, std::shared_ptr<std::string>> cache;
    double total = 0; uint64_t sum = 0;
    for (int r = 0; r < rounds; r++) {
        for (auto &s : shapes) { s->step(0.016f); total += s->area(); }
        for (auto &p : ps) {
            p.vy -= 9.8f * 0.016f; p.x += p.vx * 0.016f; p.y += p.vy * 0.016f; p.z += p.vz * 0.016f; p.life -= 0.016f;
            if (p.y < 0) { p.y = -p.y; p.vy = -p.vy * 0.7f; }
            if (p.life < 0) { p.life = 50; p.kind = clampi(p.kind + 1, 0, 3); }
            total += length3(p.vx, p.vy, p.vz);
        }
        for (int i = 0; i < 4000; i++) {
            char b[32]; snprintf(b, sizeof b, "unit_%u", rnd() % 3000);
            names[b] += i;
            uint64_t k = rnd() % 5000;
            auto it = cache.find(k);
            if (it == cache.end()) cache.emplace(k, std::make_shared<std::string>(b));
            else { std::shared_ptr<std::string> c = it->second; sum += c->size() + c.use_count(); }
        }
        std::vector<std::pair<int, float>> order;
        for (int i = 0; i < 5000; i++) order.emplace_back(rnd() % 100000, float(rnd() % 1000));
        std::sort(order.begin(), order.end(), [](const auto &a, const auto &b) { return a.second != b.second ? a.second < b.second : a.first < b.first; });
        sum += order[2500].first + fib(18);
    }
    for (auto &n : names) sum += n.second * 31 + n.first.size();
    printf("%.3f %llu %zu %zu\n", total, (unsigned long long)sum, names.size(), cache.size());
    return 0;
}
