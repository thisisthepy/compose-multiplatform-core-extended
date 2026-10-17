/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

/*
 * Skiko_GetAWT, for an embedder that has no AWT at all.
 *
 * Skiko's own (jawt.cc) opens <java.home>/lib/libjawt by path, and static_jawt.c calls the
 * linked-in JAWT_GetAWT; either one makes libjawt (and with it libawt) part of the
 * process. An embedder that draws into a window it opened itself never asks for a
 * drawing surface, so this answers "no AWT" and references nothing from JAWT. The
 * Java side (AWT.kt) is the only caller, and only for a SkiaLayer inside an AWT window.
 */
#include <jni.h>
#include <jawt.h>

jboolean Skiko_GetAWT(JNIEnv *env, JAWT *awt) {
    (void)env;
    (void)awt;
    return JNI_FALSE;
}
