# Wi-Fi Router

Add configurable Wi-Fi-style networking to your ComputerCraft world with a craftable router block for **NeoForge** and **CC:Tweaked**.

Routers create separate LANs with DHCP-assigned IP addresses, domain names mapped to individual computers, and message passing. Router bridges share domain records with directly linked WAN peers, allowing a sender to address a computer by name instead of supplying its router WAN address and LAN IP.

The `wifi` Lua module is available globally. Client computers can discover and join routers over the air with a wireless modem, without GPS setup or a wired/peripheral connection. A bridge computer running `wifi.run()` must remain attached to each router.

## Features

- **SSID and password settings** for each router. Changing credentials disconnects current LAN clients.
- **Configurable nearby-router discovery:** attached routers scan loaded chunks within 60 blocks by default. Wireless reach depends on the CC:Tweaked modem network.
- **Nearby router discovery** without GPS setup.
- **Persistent LAN and WAN domains** protected by ownership tokens, including offline reservations advertised to linked peers.
- **WAN routing** between routers using `dimension.x.y.z` addresses.
- **Wired and wireless router links** through CC:Tweaked modems and rednet.
- **Lua module API:** connect, register domains, inspect known records, and send or receive messages with `require("wifi")`.
- **Protected traffic:** password-protected LAN packets and WAN data use authenticated encryption; open LAN packets are authenticated but remain readable.
- **Protected domain announcements:** trusted WAN routers use a shared secret and reject forged or replayed records.

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

For WAN links, configure the same strong, randomly generated secret on each trusted router from an attached computer. WAN linking is disabled until configured:
```lua
local wifi = require("wifi")
assert(wifi.configureWan("replace-with-a-long-random-secret"))
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

Use `wifi.listNearbyRouters()` to discover routers, `wifi.getStatus()` to inspect your connection, and `wifi.getDomainRecords()` to list known local and directly linked peer domains. Domains must be unique across linked routers.

Use wireless modems for over-air links or wired modems for cable connections. Routers need matching WAN secrets but do not need matching LAN SSIDs or passwords. WAN sends require a direct peer route, and accepting a send does not guarantee delivery.

## Configuration and Security Notes

Wireless sessions and WAN data use random server-held keys. Open LAN traffic is authenticated but not encrypted, except ownership tokens, which are always encrypted in transit. WAN domain announcements are authenticated, encrypted, and replay-checked. Offline reservations are advertised to linked peers; unlinked routers cannot coordinate and conflicting names become ambiguous. Domain-addressed WAN packets are rejected if the destination no longer owns the name, but WAN data packets can still be replayed while the server is running. Reservations persist while the router block remains in the world. Trust only attached computers and routers with the shared WAN secret; tokens are stored on computers, and the WAN secret and LAN password are saved in world data. Discovery reveals router information, and rednet reveals traffic timing and approximate packet size. Use strong passwords because captured login proofs may allow offline guessing.

**This mod provides an in-game network, not a connection to real Wi-Fi or the internet.**