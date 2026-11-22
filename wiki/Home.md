# Wi-Fi Router Wiki

Wi-Fi Router is a NeoForge mod for Minecraft 1.21.1 that adds a craftable CC:Tweaked router peripheral and a `wifi` Lua module. It provides in-game networking, not a connection to real Wi-Fi or the internet.

## Features

- Separate router LANs with assigned addresses and optional static addresses.
- Wireless clients and attached computers communicating by LAN address or domain.
- Persistent, token-owned domain reservations with backup and recovery.
- Authenticated direct WAN links between routers, with encrypted WAN data.
- Password-protected wireless LAN traffic; open networks remain readable.

Each router has a WAN address derived from its dimension and coordinates. LAN addresses can repeat across routers. WAN bridges must be direct rednet peers; there is no automatic multi-hop routing.

## Documentation

- [Quick Start](Quick-Start)
- [API Reference](API-Reference)
- [Configuration](Configuration)
- [Security](Security)
- [Examples](Examples)
- [Troubleshooting](Troubleshooting)

Wireless clients need a wireless modem and a bridge computer running `wifi.run()` on the router. WAN linking also needs a bridge on each router and a matching shared WAN secret. See [Security](Security) for limitations and [Configuration](Configuration) for server settings.

The mod requires Minecraft 1.21.1, NeoForge 21.1.265 or newer in the 21.1 line, CC:Tweaked 1.121.0 for Minecraft 1.21.1 / NeoForge, and Java 21. See the [repository](https://github.com/Zero-Creates-Devlopment/CC-Tweaked-Wifi) for releases and issue reporting.
