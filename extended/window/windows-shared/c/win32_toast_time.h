#ifndef DXC_WIN32_TOAST_TIME_H
#define DXC_WIN32_TOAST_TIME_H

#include <stdint.h>

// The time arithmetic behind a scheduled toast (FR-36).
//
// Kept apart from win32_notifications.c like win32_scheme.h: it is a conversion and a
// comparison, and a machine with no Windows on it can compile this header and check the
// answers.

/** Milliseconds from 1601-01-01 to 1970-01-01, the gap between FILETIME and Unix time. */
#define DXC_TOAST_EPOCH_GAP_MS 11644473600000LL

/** The latest delivery time the conversion accepts; later ones would overflow 64 bits of ticks. */
#define DXC_TOAST_MAX_DELIVER_MS 910692730085477LL

/**
 * A Unix time in milliseconds as a Windows.Foundation.DateTime: 100 nanosecond ticks since
 * 1601-01-01 UTC.
 */
static inline int64_t dxc_toast_ticks_from_unix_ms(int64_t unix_ms) {
    return (unix_ms + DXC_TOAST_EPOCH_GAP_MS) * 10000LL;
}

/**
 * 1 when a toast asked for at `deliver_at_ms` is scheduled rather than shown now: the time is
 * set (zero means now), is in the future and is one the conversion can hold. Anything else is
 * shown at once, because a reminder that is already due should not be lost.
 */
static inline int32_t dxc_toast_is_scheduled(int64_t deliver_at_ms, int64_t now_ms) {
    return deliver_at_ms > 0 && deliver_at_ms > now_ms && deliver_at_ms <= DXC_TOAST_MAX_DELIVER_MS ? 1 : 0;
}

#endif
