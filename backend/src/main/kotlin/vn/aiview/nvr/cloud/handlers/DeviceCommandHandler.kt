package vn.aiview.nvr.cloud.handlers

import kotlinx.serialization.json.*
import vn.aiview.nvr.cloud.CommandDispatcher
import vn.aiview.nvr.cloud.DeviceConstants
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.utils.AppLogger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

object DeviceCommandHandler {
    fun handle(func: String, data: JsonObject): JsonObject = when (func) {
        "get_information" -> getInformation()
        "get_time" -> getTime()
        "get_ethernet" -> getEthernet()
        "get_wifi" -> getWifi()
        "get_user_management" -> getUserManagement()
        "post_reboot" -> postReboot()
        "get_storages" -> getStorages()
        else -> CommandDispatcher.errorResponse("E0008", "Function not supported: $func")
    }

    private fun getInformation() = wrapData("get.base_info.success", "I3000", buildJsonObject {
        put("firmware_version", DeviceConstants.FIRMWARE_VERSION)
        put("manufacturer", DeviceConstants.MANUFACTURER)
        put("model", DeviceConstants.MODEL)
        put("number_of_channel", DeviceConstants.NUMBER_OF_CHANNEL)
        put("product_number", DeviceConstants.PRODUCT_NUMBER)
        put("serial_number", SignalingManager.deviceSerial)
        put("web_version", DeviceConstants.WEB_VERSION)
    })

    private fun getTime(): JsonObject {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return wrapData("get.datetime.success", "I3000", buildJsonObject {
            put("datetime", sdf.format(Date()))
            put("mode", "Auto")
            putJsonArray("mode_list") { add("Auto"); add("Manual") }
            put("ntpServerAddress", "time.google.com")
            put("timezone", "(GMT+7:00) Bangkok, Hanoi, Jakarta")
            putJsonArray("timezone_list") { add("(GMT+7:00) Bangkok, Hanoi, Jakarta") }
        })
    }

    private fun getEthernet(): JsonObject {
        val (ip, mac, subnetMask) = getLanInfo()
        return wrapData("get.ip.success", "I3000", buildJsonObject {
            putJsonObject("ipv4") {
                put("dns1", "8.8.8.8")
                put("dns2", "8.8.4.4")
                put("gateway", guessGateway(ip))
                put("hostname", "nvr-aiview")
                put("ip_address", ip)
                put("mac_address", mac)
                put("subnet_mask", subnetMask)
            }
            putJsonObject("ipv6") {
                put("ip_address", "")
                put("ip_type", "")
                put("prefix", "")
                put("use", false)
            }
            put("mode", "DHCP")
            putJsonArray("mode_list") { add("DHCP"); add("Static") }
        })
    }

    private fun getWifi(): JsonObject {
        val (ip, _, _) = getLanInfo()
        return wrapData("get.wifi.success", "I3000", buildJsonObject {
            put("enable", false)
            put("ssid", "")
            put("password", "")
            put("security", "WPA2")
            putJsonObject("ipv4") {
                put("ip", ip)
                put("netmask", "255.255.255.0")
                put("gateway", guessGateway(ip))
                put("dns1", "8.8.8.8")
                put("dns2", "8.8.4.4")
            }
            put("mode", "DHCP")
            putJsonArray("mode_list") { add("DHCP"); add("Static") }
            put("signal_strength", 0)
        })
    }

    private fun getUserManagement(): JsonObject {
        val usersArray = buildJsonArray {
            addJsonObject {
                put("user_name", "admin")
                put("role", "Administrator")
            }
        }
        return buildJsonObject {
            put("status", true)
            put("message", "get.user_manage.success")
            put("error_code", "I3000")
            put("data", usersArray)
        }
    }

    private fun postReboot(): JsonObject {
        AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "REBOOT_REQUESTED", "stub — not executing")
        return wrapData("post.reboot.success", "", buildJsonObject { put("status", "") })
    }

    private fun getStorages(): JsonObject {
        return wrapData("get.storages.success", "", buildJsonObject {
            putJsonArray("storages") {
                addJsonObject {
                    put("name", "Main Storage")
                    put("diskState", "active")
                    put("totalSpace", DeviceConstants.PLACEHOLDER_STORAGE_TOTAL_MB)
                    put("freeSpace", DeviceConstants.PLACEHOLDER_STORAGE_FREE_MB)
                    put("usedSpace", DeviceConstants.PLACEHOLDER_STORAGE_USED_MB)
                    put("systemPartition", false)
                    put("type", "disk")
                }
            }
            putJsonObject("memory") {
                put("total", DeviceConstants.PLACEHOLDER_MEMORY_TOTAL_MB)
                put("free", DeviceConstants.PLACEHOLDER_MEMORY_FREE_MB)
                put("used", DeviceConstants.PLACEHOLDER_MEMORY_USED_MB)
            }
        })
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private data class LanInfo(val ip: String, val mac: String, val subnetMask: String)

    private fun getLanInfo(): LanInfo {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val lan = ifaces
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot {
                    val n = it.name.lowercase()
                    n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") ||
                        n.startsWith("tailscale") || n.startsWith("tun") || n.startsWith("tap") || n == "lo"
                }
                .filter {
                    val n = it.name.lowercase()
                    n.startsWith("eth") || n.startsWith("en") || n.startsWith("wl")
                }
                .firstOrNull { ni ->
                    ni.inetAddresses.toList().any { it is Inet4Address && isPrivateLan(it.hostAddress) }
                }

            if (lan != null) {
                val addr = lan.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { isPrivateLan(it.hostAddress) }
                val ifAddr = lan.interfaceAddresses.firstOrNull { it.address == addr }
                val subnetMask = ifAddr?.networkPrefixLength?.let { prefixToSubnet(it) } ?: "255.255.255.0"
                val mac = lan.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: ""
                LanInfo(addr?.hostAddress ?: "0.0.0.0", mac, subnetMask)
            } else {
                AppLogger.warn(AppLogger.Module.SYSTEM, "DEVICE", "LAN_IFACE_NOT_FOUND", "no eligible LAN interface")
                LanInfo("0.0.0.0", "", "255.255.255.0")
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "DEVICE", "LAN_IFACE_NOT_FOUND", e.message ?: "")
            LanInfo("0.0.0.0", "", "255.255.255.0")
        }
    }

    private fun isPrivateLan(ip: String) =
        ip.startsWith("192.168.") || ip.startsWith("10.") ||
            ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\..*"))

    private fun guessGateway(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else "192.168.1.1"
    }

    private fun prefixToSubnet(prefix: Short): String {
        val mask = -1 shl (32 - prefix)
        return listOf(mask ushr 24, (mask ushr 16) and 0xFF, (mask ushr 8) and 0xFF, mask and 0xFF)
            .joinToString(".") { (it and 0xFF).toString() }
    }

    private fun wrapData(message: String, errorCode: String, inner: JsonObject): JsonObject =
        buildJsonObject {
            put("status", true)
            put("message", message)
            put("error_code", errorCode)
            put("data", inner)
        }
}
