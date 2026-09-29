// fxpresent: a game's frames go from Mesa straight to the app, not through the X server
// (PLAN.md D3, NOTES.md N-048).
//
// Mesa's X11 swapchain (software present, patches/mesa/0007) connects to the app's socket
// (SOCK_SEQPACKET, path in FEXDROID_PRESENT), says hello and hands over a memfd with room
// for FXPRESENT_SLOTS frames. For every frame it copies the pixels into a slot the app is
// not reading, publishes the slot and sends fxpresent_frame. The app copies the published
// slot to its window. The same definitions are in the Mesa patch: keep them alike.
#ifndef FXPRESENT_PROTO_H
#define FXPRESENT_PROTO_H
#include <stdatomic.h>
#include <stdint.h>

#define FXPRESENT_MAGIC 0x50445846u /* "FXDP" */
#define FXPRESENT_VERSION 1
#define FXPRESENT_SLOTS 3
#define FXPRESENT_NONE 0xffffffffu

struct fxpresent_hello { // first message, with the memfd as SCM_RIGHTS
    uint32_t magic, version;
    uint32_t width, height; // of a frame, in pixels of 4 bytes: B, G, R, X
    uint32_t stride;        // bytes from one row to the next
    uint32_t slots;
    uint32_t first_frame;   // offset of slot 0 in the memory
    uint32_t frame_size;    // bytes from one slot to the next
    int32_t pid;
};

struct fxpresent_shared { // at the start of the memory
    _Atomic uint32_t published; // the slot with the newest whole frame, or FXPRESENT_NONE
    _Atomic uint32_t reading;   // the slot the app copies from, or FXPRESENT_NONE
    _Atomic uint64_t frames;    // published so far
    _Atomic int32_t x, y;       // where the frame's corner is on the X screen
};

struct fxpresent_frame { // one for every published frame
    uint64_t frames;
};
#endif
