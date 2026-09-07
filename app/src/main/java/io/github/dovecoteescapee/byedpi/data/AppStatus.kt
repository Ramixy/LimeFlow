package io.github.dovecoteescapee.byedpi.data

enum class AppStatus {
    Halted,
    Running,
}

enum class Mode {
    Proxy,
    VPN;

    companion object {
        fun fromSender(sender: Sender): Mode = when (sender) {
            Sender.Proxy -> Proxy
            Sender.VPN -> VPN
        }

        fun fromString(name: String): Mode = when (name) {
            "proxy" -> Proxy
            // A corrupted/imported preference must not crash the app at startup.
            else -> VPN
        }
    }
}