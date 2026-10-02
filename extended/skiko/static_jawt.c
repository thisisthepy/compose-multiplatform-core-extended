/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

/*
 * Skiko's Skiko_GetAWT, for when Skia and AWT are both linked into the executable.
 *
 * Skiko's own (jawt.cc) opens <java.home>/lib/libjawt.dylib by path and looks JAWT_GetAWT
 * up in it. A native image has no java.home and, built as one file, no libjawt beside it:
 * JAWT is linked in, so the function is called directly. The static skiko archive is built
 * with this object in place of skiko's jawt.o.
 */
#include <jni.h>
#include <jawt.h>

jboolean Skiko_GetAWT(JNIEnv *env, JAWT *awt) {
    return JAWT_GetAWT(env, awt);
}
