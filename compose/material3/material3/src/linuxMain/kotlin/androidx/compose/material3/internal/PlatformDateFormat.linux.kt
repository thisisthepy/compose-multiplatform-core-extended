/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalTime::class, FormatStringsInDatetimeFormats::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import androidx.compose.ui.util.fastFlatMap
import androidx.compose.ui.util.fastMap
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Dates, without a locale library to format them with.
 *
 * The platforms that have one hand it the pattern and the locale and take back a string
 * that reads the way that locale reads. There is none here that can be relied on: glibc
 * has one and musl does not, and what either carries depends on which locales the machine
 * was built with. So the patterns are applied literally and the names are English.
 *
 * What that costs is the month and day names in the date picker, and the order of the
 * fields where a locale would have chosen a different one. What it does not cost is the
 * arithmetic: which day a date falls on, how many days a month has, and what the week
 * starts on are the same everywhere and are answered here exactly.
 */
internal actual class PlatformDateFormat actual constructor(private val locale: CalendarLocale) {
    private val firstDaysOfWeekByRegionCode: Map<String, Int> by lazy {
        listOf(
            7 to listOf("TH", "ET", "SG", "JM", "BT", "IN", "US", "MO", "KE", "DO", "AU", "IL", "AS", "TW", "MZ", "MM", "CN", "PR", "PK", "BD", "NP", "HN", "BR", "HK", "TT", "ZA", "VE", "MT", "PH", "PE", "ID", "DM", "WS", "ZW", "UM", "LA", "BZ", "JP", "SV", "SA", "CO", "GT", "BW", "KR", "PA", "YE", "BS", "MX", "MH", "GU", "PY", "AG", "CA", "KH", "PT", "VI", "NI"),
            6 to listOf("EG", "AF", "SY", "IR", "OM", "IQ", "DZ", "DJ", "AE", "SD", "KW", "JO", "BH", "QA", "LY")
        ).fastFlatMap { (day, tags) -> tags.fastMap { it to day } }.toMap()
    }

    private val regionsWith12HourFormat by lazy {
        listOf("AE", "AG", "AL", "AS", "AU", "BB", "BD", "BH", "BM", "BN", "BS", "BT", "CA", "CN", "CO", "CY", "DJ", "DM", "DO", "DZ", "EG", "EH", "ER", "ET", "FJ", "FM", "GD", "GH", "GM", "GR", "GU", "GY", "HK", "IN", "IQ", "JM", "JO", "KH", "KI", "KN", "KP", "KR", "KW", "KY", "LB", "LC", "LR", "LS", "LY", "MH", "MO", "MP", "MR", "MW", "MY", "NA", "NZ", "OM", "PA", "PG", "PH", "PK", "PR", "PS", "PW", "QA", "SA", "SB", "SD", "SG", "SL", "SO", "SS", "SY", "SZ", "TC", "TD", "TN", "TO", "TT", "TW", "UM", "US", "VC", "VE", "VG", "VI", "VU", "WS", "YE", "ZM")
    }

    actual val firstDayOfWeek: Int
        get() = firstDaysOfWeekByRegionCode[locale.region.uppercase()] ?: 1

    // Monday first, because that is the order the caller reads them in, and both the full
    // name and the short one, which is what a date picker puts in its header.
    actual val weekdayNames: List<Pair<String, String>>
        get() = listOf(
            "Monday" to "Mon",
            "Tuesday" to "Tue",
            "Wednesday" to "Wed",
            "Thursday" to "Thu",
            "Friday" to "Fri",
            "Saturday" to "Sat",
            "Sunday" to "Sun",
        )

    actual fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        cache: MutableMap<String, Any>,
    ): String {
        val format = cache.getOrPut(pattern) {
            LocalDate.Format { byUnicodePattern(pattern) }
        } as kotlinx.datetime.format.DateTimeFormat<LocalDate>
        return Instant.fromEpochMilliseconds(utcTimeMillis)
            .toLocalDateTime(TimeZone.UTC)
            .date
            .format(format)
    }

    // A skeleton says which fields to show and leaves their order to the locale. With no
    // locale to ask, it is read as a pattern, which is the same thing for the skeletons
    // the date picker actually uses.
    actual fun formatWithSkeleton(
        utcTimeMillis: Long,
        skeleton: String,
        cache: MutableMap<String, Any>,
    ): String = formatWithPattern(utcTimeMillis, skeleton, cache)

    actual fun parse(
        date: String,
        pattern: String,
        locale: CalendarLocale,
        cache: MutableMap<String, Any>,
    ): CalendarDate? {
        val format = cache.getOrPut(pattern) {
            LocalDate.Format { byUnicodePattern(pattern) }
        } as kotlinx.datetime.format.DateTimeFormat<LocalDate>
        val parsed = try {
            LocalDate.parse(date, format)
        } catch (error: IllegalArgumentException) {
            return null
        }
        return CalendarDate(
            year = parsed.year,
            month = parsed.monthNumber,
            dayOfMonth = parsed.dayOfMonth,
            utcTimeMillis = parsed.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
    }

    actual fun getDateInputFormat(): DateInputFormat =
        DateInputFormat(patternWithDelimiters = "MM/dd/yyyy", delimiter = '/')

    actual fun is24HourFormat(): Boolean {
        val region = locale.region.uppercase()
        return regionsWith12HourFormat.binarySearch { it.compareTo(region) } < 0
    }
}
