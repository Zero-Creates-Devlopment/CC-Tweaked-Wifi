# Wi-Fi Router

Add configurable Wi-Fi-style networking to your ComputerCraft world with a craftable router block for **NeoForge** and **CC:Tweaked**.

Routers create separate LANs with assigned IP addresses, domain names mapped to individual computers, and message passing. Loaded routers share domain records automatically across the server, including across dimensions.

The `wifi` Lua module is available globally. Client computers can discover and join routers over the air with a wireless modem, without GPS setup or a wired/peripheral connection. Wireless clients need a bridge computer running `wifi.run()` on their router.

## Features

- **SSID and password settings** for each router. Changing credentials disconnects current LAN clients.
- **Configurable nearby-router discovery:** attached routers scan loaded chunks within 60 blocks by default. Wireless reach depends on the CC:Tweaked modem network.
- **Nearby router discovery** without GPS setup.
- **Persistent LAN and WAN domains** protected by ownership tokens, including offline reservations shared by loaded routers.
- **WAN routing** between routers using `dimension.x.y.z` addresses.
- **Automatic server-wide routing** without router links, modem reach, or a WAN secret.
- **Lua module API:** connect, register domains, inspect known records, and send or receive messages with `require("wifi")`.
- **Protected wireless traffic:** password-protected LAN packets use authenticated encryption; open LAN packets are authenticated but remain readable.

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

For wireless clients, keep a bridge computer with a wireless modem attached to the router:
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

Registration saves an ownership token on the computer and reserves the name on the router across disconnects and restarts. Back it up privately with `wifi.exportDomainToken("storage.example")`; import it on another computer with `wifi.importDomainToken(name, token)`. Only a token holder can release a reservation with `wifi.releaseDomain(name)`. Losing every copy of a token makes that reservation unrecoverable through the API.

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

Use `wifi.listNearbyRouters()` to discover routers, `wifi.getStatus()` to inspect your connection, and `wifi.getDomainRecords()` to list domains on loaded routers.

Loaded routers communicate across the server without configuration, including across dimensions. Unloaded chunks are not force-loaded. A successful send places a message in the recipient's inbox but does not guarantee the application reads it.

## Configuration and Security Notes

Wireless sessions use random server-held keys. Open LAN traffic is authenticated but not encrypted, except ownership tokens, which are always encrypted in transit. Router-to-router messages and domain records remain inside the server and do not use WAN packet encryption. Offline reservations on loaded routers prevent conflicting claims; unloaded routers cannot coordinate, and conflicts become ambiguous when they load. Reservations persist while the router block remains in the world. Protect attached computers, world data, and token backups. Discovery reveals router information, and rednet reveals traffic timing and approximate packet size. Use strong passwords because captured login proofs may allow offline guessing.

**This mod provides an in-game network, not a connection to real Wi-Fi or the internet.**