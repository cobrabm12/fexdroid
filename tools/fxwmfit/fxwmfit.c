// SPDX-License-Identifier: MIT
// fxwmfit: keeps top-level X windows inside the screen. There is no window manager on the
// phone, so a client that asks for more than the virtual screen (Steam: 1280x800 on a
// 1280x720 display) is simply cut off. This is not a window manager: it takes no redirect,
// draws no decorations and leaves override-redirect windows (menus, tooltips) alone. It only
// moves/resizes normal top-level windows that do not fit.
//
// usage: fxwmfit [--once]      (DISPLAY from the environment)
#include <X11/Xlib.h>
#include <stdio.h>
#include <string.h>

static int on_error(Display* d, XErrorEvent* e) {
  (void)d;
  (void)e;
  return 0; // Windows disappear while we look at them: not an error for us.
}

static void fit(Display* d, Window w, int sw, int sh) {
  XWindowAttributes a;
  if (!XGetWindowAttributes(d, w, &a) || a.override_redirect || a.class != InputOutput || a.map_state != IsViewable) {
    return;
  }
  if (a.width < 64 || a.height < 64) {
    return; // Helper windows (1x1, 10x10).
  }
  int nw = a.width > sw ? sw : a.width;
  int nh = a.height > sh ? sh : a.height;
  int nx = a.x, ny = a.y;
  if (nx + nw > sw) nx = sw - nw;
  if (ny + nh > sh) ny = sh - nh;
  if (nx < 0) nx = 0;
  if (ny < 0) ny = 0;
  if (nw != a.width || nh != a.height || nx != a.x || ny != a.y) {
    XMoveResizeWindow(d, w, nx, ny, (unsigned)nw, (unsigned)nh);
    fprintf(stderr, "fxwmfit: 0x%lx %dx%d+%d+%d -> %dx%d+%d+%d\n", w, a.width, a.height, a.x, a.y, nw, nh, nx, ny);
  }
}

static void fit_all(Display* d, Window root, int sw, int sh) {
  Window r, p, *kids = NULL;
  unsigned n = 0;
  if (XQueryTree(d, root, &r, &p, &kids, &n)) {
    for (unsigned i = 0; i < n; i++) {
      fit(d, kids[i], sw, sh);
    }
    if (kids) XFree(kids);
  }
}

int main(int argc, char** argv) {
  Display* d = XOpenDisplay(NULL);
  if (!d) {
    fprintf(stderr, "fxwmfit: cannot open display\n");
    return 1;
  }
  XSetErrorHandler(on_error);
  int s = DefaultScreen(d);
  Window root = RootWindow(d, s);
  int sw = DisplayWidth(d, s), sh = DisplayHeight(d, s);
  fit_all(d, root, sw, sh);
  XSync(d, False);
  if (argc > 1 && strcmp(argv[1], "--once") == 0) {
    return 0;
  }
  XSelectInput(d, root, SubstructureNotifyMask);
  for (;;) { // Ends with the X server (Xlib exits on the I/O error).
    XEvent e;
    XNextEvent(d, &e);
    if (e.type == MapNotify && e.xmap.event == root) {
      fit(d, e.xmap.window, sw, sh);
    } else if (e.type == ConfigureNotify && e.xconfigure.event == root) {
      fit(d, e.xconfigure.window, sw, sh);
    }
  }
}
