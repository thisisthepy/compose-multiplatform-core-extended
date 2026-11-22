package org.thisisthepy.compose.designsystem

/**
 * Marks a Layer 1 component that has no native counterpart in the target platform's design
 * guidelines and is instead drawn in that system's visual style.
 *
 * Callers that want the platform's idiomatic substitute by default should use the Layer 2
 * `org.thisisthepy.compose.adaptive` API instead.
 */
@RequiresOptIn(
    message = "This component is not a native pattern of this design system; prefer org.thisisthepy.compose.adaptive for the platform substitute.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class NonNativeComponent
