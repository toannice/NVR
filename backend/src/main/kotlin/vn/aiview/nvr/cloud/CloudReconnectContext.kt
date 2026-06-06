package vn.aiview.nvr.cloud

object CloudReconnectContext {
    var reconnect: (() -> Unit)? = null
}
