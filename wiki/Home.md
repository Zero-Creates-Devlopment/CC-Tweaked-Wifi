# Wi-Fi Router Wiki

Wi-Fi Router is a NeoForge mod for Minecraft 26.3 that adds a craftable CC:Tweaked router peripheral and a `wifi` Lua module. It provides in-game networking, not a connection to real Wi-Fi or the internet.

## Features

- Separate router LANs with assigned addresses and optional static addresses.
- Wireless clients and attached computers communicating by LAN address or domain.
- Persistent, token-owned domain reservations with backup and recovery.
- Automatic server-wide routing and domain sharing between loaded routers.
- Password-protected wireless LAN traffic; open networks remain readable.

Each router gets a random IPv4-style WAN address that remains saved with that router. LAN addresses can repeat across routers. Loaded routers communicate server-wide, including across dimensions, without linking or modem reach.

## Documentation

- [Quick Start](Quick-Start)
- [API Reference](API-Reference)
- [Configuration](Configuration)
- [Security](Security)
- [Examples](Examples)
- [Troubleshooting](Troubleshooting)

Wireless clients need a wireless modem and a bridge computer running `wifi.run()` on the router. Router-to-router routing does not require a bridge or secret. See [Security](Security) for limitations and [Configuration](Configuration) for server settings.

The mod requires Minecraft 26.3, NeoForge 26.3.0.115 or newer in the 26.3 line, CC:Tweaked 1.121.0 for Minecraft 26.3 / NeoForge, and Java 25. See the [repository](https://github.com/Zero-Creates-Devlopment/CC-Tweaked-Wifi) for releases and issue reporting.
