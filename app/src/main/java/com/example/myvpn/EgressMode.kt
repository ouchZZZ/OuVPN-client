package com.example.myvpn

internal enum class EgressMode {
    HOME, NODE;

    companion object {
        fun resolve(homeEnabled: Boolean, hasConfig: Boolean): EgressMode = if (homeEnabled && hasConfig) HOME else NODE
    }
}
