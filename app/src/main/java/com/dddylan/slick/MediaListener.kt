package com.dddylan.slick

import android.service.notification.NotificationListenerService

/**
 * Android only shares other apps' media sessions (title, artist, play/pause) with an app that holds
 * notification access. This empty listener is what the user grants that access to; Slick never reads
 * notifications themselves.
 */
class MediaListener : NotificationListenerService()
