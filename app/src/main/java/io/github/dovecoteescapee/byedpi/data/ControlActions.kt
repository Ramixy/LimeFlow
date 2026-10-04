package io.github.dovecoteescapee.byedpi.data

/**
 * External control API shared by ShortcutActivity (launcher shortcuts and
 * assistant voice commands), ControlReceiver (automation apps holding the
 * signature CONTROL permission) and the limeflow:// deeplinks.
 */
const val CONTROL_PERMISSION = "app.alt11.mobile.permission.CONTROL"

const val CONTROL_ACTION_CONNECT = "app.alt11.mobile.CONNECT"
const val CONTROL_ACTION_DISCONNECT = "app.alt11.mobile.DISCONNECT"
const val CONTROL_ACTION_TOGGLE = "app.alt11.mobile.TOGGLE"
const val CONTROL_ACTION_STATUS = "app.alt11.mobile.STATUS"
const val CONTROL_ACTION_STATUS_RESPONSE = "app.alt11.mobile.STATUS_RESPONSE"

const val VOICE_ACTION_CONNECT = "app.alt11.mobile.VOICE_CONNECT"
const val VOICE_ACTION_DISCONNECT = "app.alt11.mobile.VOICE_DISCONNECT"
const val VOICE_ACTION_TOGGLE = "app.alt11.mobile.VOICE_TOGGLE"

const val CONTROL_EXTRA_STATUS = "status"
const val CONTROL_EXTRA_MODE = "mode"
const val CONTROL_EXTRA_PAUSED = "paused"

const val STATUS_VALUE_CONNECTED = "connected"
const val STATUS_VALUE_DISCONNECTED = "disconnected"

const val MODE_VALUE_VPN = "vpn"
const val MODE_VALUE_PROXY = "proxy"
const val MODE_VALUE_ZAPRET = "zapret"

const val DEEPLINK_SCHEME = "limeflow"
