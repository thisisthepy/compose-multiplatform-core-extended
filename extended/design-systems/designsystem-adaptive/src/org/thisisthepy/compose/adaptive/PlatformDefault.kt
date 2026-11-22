package org.thisisthepy.compose.adaptive

import org.thisisthepy.compose.designsystem.DesignSystemId

/** Returns the default [DesignSystemId] for the host operating system / desktop environment. */
expect fun platformDefaultDesignSystemId(): DesignSystemId
