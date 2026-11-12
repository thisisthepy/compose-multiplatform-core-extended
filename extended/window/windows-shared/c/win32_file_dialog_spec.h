// The text a file dialog's type list is made from, kept apart from win32_file_dialog.c so
// the shared test can check it without a dialog. Nothing here includes windows.h.
//
// The caller hands the filters over as one UTF-8 string, one line per filter:
//
//     label <tab> ext,ext,ext
//
// Extensions are written without a dot. A line with no tab, or with nothing after it, is a
// filter for every file. Empty lines are not filters.
#ifndef DXC_WIN32_FILE_DIALOG_SPEC_H
#define DXC_WIN32_FILE_DIALOG_SPEC_H

#include <stddef.h>
#include <stdint.h>

/**
 * The filter at [index]: its label and its extension list, neither NUL terminated.
 * 1 when there is such a filter, 0 when [index] is past the last one.
 */
static inline int32_t dxc_file_filter_at(const char *filters, int32_t index, const char **label,
                                         size_t *label_length, const char **extensions,
                                         size_t *extensions_length) {
    if (filters == NULL || index < 0) return 0;
    const char *line = filters;
    int32_t seen = 0;
    while (*line != '\0') {
        const char *end = line;
        while (*end != '\0' && *end != '\n') end++;
        if (end > line) {
            if (seen == index) {
                const char *tab = line;
                while (tab < end && *tab != '\t') tab++;
                *label = line;
                *label_length = (size_t)(tab - line);
                *extensions = tab < end ? tab + 1 : end;
                *extensions_length = tab < end ? (size_t)(end - tab - 1) : 0;
                return 1;
            }
            seen++;
        }
        if (*end == '\0') break;
        line = end + 1;
    }
    return 0;
}

/** How many filters [filters] holds. */
static inline int32_t dxc_file_filter_count(const char *filters) {
    const char *label, *extensions;
    size_t label_length, extensions_length;
    int32_t count = 0;
    while (dxc_file_filter_at(filters, count, &label, &label_length, &extensions, &extensions_length)) {
        count++;
    }
    return count;
}

/**
 * The pattern the dialog matches file names against: `*.png;*.jpg` for `png,jpg`, and `*.*`
 * for no extensions. Empty entries are skipped. Writes at most [capacity] bytes with the
 * ending NUL and returns the length the whole pattern has without it, so a caller measures
 * with a capacity of 0 and then writes.
 */
static inline size_t dxc_file_filter_pattern(const char *extensions, size_t length, char *out,
                                             size_t capacity) {
    size_t written = 0;
    size_t start = 0;
#define DXC_FILTER_PUT(c) \
    do { if (written + 1 < capacity) out[written] = (c); written++; } while (0)
    for (size_t at = 0; at <= length; at++) {
        if (at < length && extensions[at] != ',') continue;
        if (at > start) {
            if (written > 0) DXC_FILTER_PUT(';');
            DXC_FILTER_PUT('*');
            DXC_FILTER_PUT('.');
            for (size_t index = start; index < at; index++) DXC_FILTER_PUT(extensions[index]);
        }
        start = at + 1;
    }
    if (written == 0) {
        DXC_FILTER_PUT('*');
        DXC_FILTER_PUT('.');
        DXC_FILTER_PUT('*');
    }
#undef DXC_FILTER_PUT
    if (capacity > 0) out[written < capacity ? written : capacity - 1] = '\0';
    return written;
}

/**
 * The first extension of the list, which a save adds to a name typed without one. Returns
 * its length, 0 when the list names none, and points [first] at it.
 */
static inline size_t dxc_file_filter_first_extension(const char *extensions, size_t length,
                                                     const char **first) {
    size_t start = 0;
    for (size_t at = 0; at <= length; at++) {
        if (at < length && extensions[at] != ',') continue;
        if (at > start) {
            *first = extensions + start;
            return at - start;
        }
        start = at + 1;
    }
    *first = extensions;
    return 0;
}

#endif
