# Wi-Fi Router

Add configurable Wi-Fi-style networking to your ComputerCraft world with a craftable router block for **NeoForge** and **CC:Tweaked**.

Routers create separate LANs with DHCP-assigned IP addresses, domain names mapped to individual computers, and message passing. Router bridges share domain records with directly linked WAN peers, allowing a sender to address a computer by name instead of supplying its router WAN address and LAN IP.

The `wifi` Lua module is available globally. Client computers can discover and join routers over the air with a wireless modem, without GPS setup or a wired/peripheral connection. A bridge computer running `wifi.run()` must remain attached to each router.

## Features

- **SSID and password settings** for each router. Changing credentials disconnects current LAN clients.
- **Configurable nearby-router discovery:** attached routers scan loaded chunks within 60 blocks by default. Wireless reach depends on the CC:Tweaked modem network.
- **Nearby router discovery** without GPS setup.
- **LAN and WAN domains** that map a name to a specific computer and support domain-based WAN messaging.
- **WAN routing** between routers using `dimension.x.y.z` addresses.
- **Wired and wireless router links** through CC:Tweaked modems and rednet.
- **Lua module API:** connect, register domains, inspect known records, and send or receive messages with `require("wifi")`.
- **Automatic WAN data encryption:** WAN messages are encrypted and authenticated with AES-256-GCM when sent and decrypted when received, using a random in-memory server key.

## Requirements

- NeoForge
- CC:Tweaked

Install both the mod and CC:Tweaked in your `mods` folder. A CC:Tweaked wireless modem is required for air connections and discovery; no GPS network is required.

## Quick Start

Attach a computer to the router to configure its network:
```lua
local wifi = require("wifi")
assert(wifi.configureWifi("WorkshopNet", "change-me-123"))
```

For wireless clients and WAN links, keep a bridge computer attached to each router. Each bridge needs a CC:Tweaked modem:
```lua
local wifi = require("wifi")
wifi.run()
```

On a client computer, join the network and register a domain:
```lua
local wifi = require("wifi")
local connected, reason = wifi.connect("WorkshopNet", "change-me-123")
assert(connected, reason)
print("LAN IP:", wifi.getIP())
assert(wifi.registerDomain("storage.example"))
```

From another connected computer, send a message to the domain:
```lua
local wifi = require("wifi")
local sent, reason = wifi.send("storage.example", { message = "hello" })
print(sent, reason)
```

On the destination computer, read the next packet:
```lua
local packet, reason = wifi.receive(5)
if packet then
  print(textutils.serialize(packet))
else
  print(reason or "No message received")
end
```

Use `wifi.listNearbyRouters()` to discover routers, `wifi.getStatus()` to inspect your connection, and `wifi.getDomainRecords()` to list known local and directly linked peer domains. Domains must be unique across linked routers.

Use wireless modems for over-air links or wired modems for cable connections. Routers do not need matching LAN SSIDs or passwords to link over WAN. WAN sends require a direct peer route, and accepting a send does not guarantee delivery.

## Configuration and Security Notes

The per-world server config includes attached-router discovery range, maximum LAN clients, inbox capacity, and bridge discovery timing. WAN data is automatically encrypted before transmission and decrypted on receipt. The random key is never saved or sent over rednet, so packets from before a server restart cannot be decrypted afterward. 

Router discovery and domain announcements are not encrypted; they can reveal SSIDs, router addresses, registered domains, and LAN IPs. The WAN link proof uses a publicly known value and is not secure peer authentication. Wireless LAN messages are not encrypted. Rednet also reveals traffic timing and approximate packet size.

**This mod provides an in-game network, not a connection to real Wi-Fi or the internet.**
