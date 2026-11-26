# Wi-Fi Router

A standalone NeoForge mod for Minecraft 26.3 that adds a craftable router block with a CC:Tweaked peripheral.

## Requirements

- Minecraft 26.3
- NeoForge 26.3.0.115 or newer in the 26.3 line
- CC:Tweaked 1.121.0 for Minecraft 26.3 / NeoForge

The CC:Tweaked 1.121.0 NeoForge build is currently a pre-release. Install CC:Tweaked alongside this mod; it is a required runtime dependency. Minecraft 26.3 requires Java 25.

## Build

Run `.\gradlew.bat build` from this folder. The mod jar is written to `build\libs\wifi-<version>.jar`, where the version is set in `build.gradle`.

Copy that jar and the CC:Tweaked jar into the Minecraft instance's `mods` folder.

## Router peripheral

The router appears in the Functional Blocks creative tab and can also be crafted from iron, quartz, redstone, and glass. The `wifi` Lua module is installed globally in ComputerCraft's ROM, so `require("wifi")` works on any computer, whether or not a router peripheral is attached.

```lua
local wifi = require("wifi")

local connected, reason = wifi.connect("MC-WiFi", "")
assert(connected, reason)
print(textutils.serialize(wifi.getStatus()))
assert(wifi.registerDomain("base.local"))
```

Change the router's SSID and password from an attached computer with:

```lua
assert(wifi.configureWifi("WorkshopNet", "change-me-123"))
```

Use an empty password for an open network. Changing either credential disconnects current LAN clients; they must reconnect with the new SSID and password.

Each router gets a random IPv4-style WAN address, such as `22.145.56.55`, which remains saved with that router. Computers connected to the same router use its private `192.168.1.x` LAN; those LAN addresses are local to that router and may repeat on another router.

Client joins require a modem. `wifi.connect(ssid, password)` discovers and joins a router over the air using a wireless modem; it does not require the client computer to be connected to the router peripheral or a wired modem and does not require GPS setup.

For a wireless client, use the module to access the assigned LAN address and exchange local messages:

```lua
local wifi = require("wifi")
assert(wifi.connect("WorkshopNet", "change-me-123"))
print("LAN IP:", wifi.getIP())
wifi.send("192.168.1.101", { message = "hello" })
local packet = wifi.receive()
```

To list discoverable routers with a wireless modem and no router peripheral:

```lua
local nearby, reason = wifi.listNearbyRouters()
assert(nearby, reason)
for _, item in ipairs(nearby) do
    print(item.ssid, item.wanIP, item.security)
end
```

Wireless discovery listens for router bridge beacons. A bridge computer running `wifi.run()` must be attached to each router. Air discovery reports SSIDs, WAN addresses, security modes, router coordinates, and client counts, but never passwords; it does not report distance. When a router peripheral is attached, discovery instead scans loaded chunks within the configured `wirelessRange` around that router and does not load chunks.

Use the `wifi` Lua module as the complete interface:

- `wifi.getStatus()` returns connection and router information.
- `wifi.getWanIP()` and `wifi.getIP()` return the WAN and LAN addresses.
- `wifi.connect(ssid, password)`, `wifi.disconnect()`, and `wifi.listNearbyRouters()` manage connections and discovery.
- `wifi.configureWifi(ssid, password)` changes LAN credentials; use an empty password for an open network.
- `wifi.setStaticIP(address)` assigns a LAN address; `wifi.getNetworkConfig()` returns server limits and timing; `wifi.getClients()` lists LAN clients.
- `wifi.registerDomain(name)` reserves a domain and activates it for this computer. `wifi.resolve(name)` returns the LAN IP for a unique online domain on a loaded router, or an empty string if it is offline, unavailable, or ambiguous. `wifi.getDomainRecords()` lists loaded routers' reservations, including offline records with `lanIP = ""` and `online = false`. Air lookups require a running bridge.
- `wifi.exportDomainToken(name)` returns a saved ownership token; `wifi.importDomainToken(name, token)` imports a backup. `wifi.releaseDomain(name)` releases a reservation on the connected router. `wifi.forgetDomainToken(name)` deletes only the local token, not the reservation.
- `wifi.send(recipient, message[, thread])` broadcasts to a WAN address or sends to a LAN IP or domain. It returns a conversation token on success.
- `wifi.receive(timeout)` receives the next packet; `wifi.receive(thread, timeout)` selects a conversation without removing other messages. `wifi.run()` runs the router bridge.
- The legacy `wifi.sendWAN(destination, lanIP, message)` remains available for targeted WAN sends.

For air-connected clients, `wifi.send` and `wifi.sendWAN` wait for the bridge to accept or reject the request and return `false` with a reason if routing fails or the bridge does not respond. A successful send places the message in the destination inbox; it does not guarantee the application reads it.

Air-connected `wifi.registerDomain` also waits for the bridge to accept or reject the registration. Sends to a domain fail when the name is offline or ambiguous.

The first registration generates a random ownership token and saves it in `/.wifi-domains/tokens` on the computer before sending the request. The router stores only its hash, and the token is encrypted in transit even on an open LAN. The last active domain for each router is automatically restored when the computer reconnects. A computer can have one active domain per router; registering a different domain leaves the previous name reserved but offline.

Back up tokens privately with `wifi.exportDomainToken(name)`. To move ownership to another computer, import the token with `wifi.importDomainToken(name, token)`, connect to the original router, then call `wifi.registerDomain(name)` after the previous computer disconnects. Import refuses to overwrite a different saved token. Only a holder of the token can release the reservation using `wifi.releaseDomain(name)`; releasing does not delete the local token. `wifi.forgetDomainToken(name)` deletes the local copy without releasing the name. **If all copies of a token are lost, the reservation cannot be released or reclaimed through the API.**

Reservations survive disconnects and server restarts while the router block remains in the world. Loaded routers share offline reservations and prevent new conflicting claims. Reservations on unloaded routers are not visible; independent claims made while a router is unloaded may become ambiguous when it loads again. Each router can retain up to 254 reservations.

An air-connected client can use `wifi.send(recipient, message)` with a local LAN address, domain, or WAN address. A WAN address broadcasts to every connected client on that router. Use `wifi.receive()` to read packets; wireless clients still need their local bridge running.

### Server configuration

NeoForge creates a per-world server config with these options:

- `wirelessRange` (default `60` blocks): scan radius for attached-peripheral nearby-router discovery. Air connections and discovery use the available CC:Tweaked modem/rednet links; no GPS setup is required.
- `maxClients` (default `155`): maximum LAN clients per router.
- `maxInboxSize` (default `64`): queued messages retained per client.
- `announceIntervalSeconds` (default `3`): wireless bridge beacon interval.
- `peerTimeoutSeconds` (default `12`): legacy setting retained for compatibility; server-wide routing does not use it.

Password-protected wireless LAN packets are encrypted and authenticated; open networks authenticate packets but do not encrypt them. The password is authenticated with a challenge response and is never sent over rednet in plaintext. Air reach depends on the CC:Tweaked modem/rednet setup.

### Server-wide routing

Loaded routers automatically share domain records and route messages server-wide, including across dimensions. No link, WAN secret, or modem connection between routers is required. Unloaded chunks are not force-loaded. Directly attached clients do not need a bridge for router-to-router traffic.

For wireless clients, run the module on an attached computer with a wireless CC:Tweaked modem:

```lua
local wifi = require("wifi")
wifi.run()
```

`wifi.run()` opens attached CC modems and handles wireless client discovery and sessions. Router LAN SSIDs and passwords do not need to match. Keep the bridge running for wireless clients. To run it alongside another program, use `parallel.waitForAny(wifi.run, yourProgram)`.

Register a domain on the destination computer:

```lua
local wifi = require("wifi")
assert(wifi.registerDomain("storage.example"))
```

The name is available to other loaded routers. Send to that computer from a wired or air-connected client with:

```lua
local wifi = require("wifi")
wifi.send("storage.example", { message = "hello" })
```

Domains must be unique across loaded routers. To broadcast and receive replies in one conversation:

```lua
local sent, thread = wifi.send("22.145.56.55", "hello everyone")
assert(sent, thread)
local reply = wifi.receive(thread, 5)
```

Recipients can read an incoming packet with `wifi.receive(5)` and reply with `wifi.send(packet.fromWan, "hello", packet.thread)`. A WAN reply also broadcasts to every connected client on the sender's router. Thread tokens correlate messages; they are not authentication or confidentiality.

Messages may be strings, numbers, booleans, or serializable Lua tables (including nested tables); nil, functions, userdata, and cyclic tables are rejected. WAN messages are limited to 64 KiB, 64 levels of nesting, and 65,536 table entries. The receiving computer reads the packet with `receive()`. A failed route returns `false`. Since LAN IPs can repeat across routers, send to a remote computer by its domain rather than by the IP returned by `wifi.resolve`.

Messages raise a `wifi_message` event and connections raise `wifi_connected` / `wifi_disconnected` events. Client sessions are held in memory and end on disconnect; domain reservations are saved with the router. Conflicting domain reservations are treated as ambiguous rather than routed. LAN credentials are saved with the router block. Trust attached computers and protect token files; anyone with a token can use its domain ownership.

Password-protected wireless packets use AES-256-GCM with random server-held session keys; open networks use authenticated but readable packets. Router-to-router messages and domain records are transferred inside the server, not over rednet, so there is no WAN secret or WAN packet encryption. A domain-addressed packet is rejected if the destination no longer owns that name. Discovery reveals SSIDs and router addresses; rednet also exposes traffic timing and approximate packet size. Captured login proofs may permit offline password guessing, so use strong LAN passwords.

This is an in-game network, not a connection to real Wi-Fi or the internet. All routers and clients must use the updated mod and module.

