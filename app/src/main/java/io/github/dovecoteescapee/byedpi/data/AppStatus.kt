package io.github.dovecoteescapee.byedpi.data

enum class AppStatus {
    Halted,
    Running,
}

enum class Mode {
    Proxy,
    VPN,
    Zapret;

    companion object {
        fun fromSender(sender: Sender): Mode = when (sender) {
            Sender.Proxy -> Proxy
            Sender.VPN -> VPN
            Sender.Zapret -> Zapret
        }

        fun fromString(name: String): Mode = when (name) {
            "proxy" -> Proxy
            "zapret" -> Zapret
            // A corrupted/imported preference must not crash the app at startup.
            else -> VPN
        }
    }
}
