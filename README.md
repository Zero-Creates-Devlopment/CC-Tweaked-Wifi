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

Each router's WAN address identifies its dimension and block coordinates, such as `minecraft:overworld.12.64.-30`. Computers connected to the same router use its private `192.168.1.x` LAN; those LAN addresses are local to that router and may repeat on another router.

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
- `wifi.configureWan(secret)` sets the shared WAN secret on an attached router; an empty secret disables WAN linking.
- `wifi.setStaticIP(address)` assigns a LAN address; `wifi.getNetworkConfig()` returns server limits and timing; `wifi.getClients()` lists LAN clients.
- `wifi.registerDomain(name)` reserves a domain and activates it for this computer. `wifi.resolve(name)` returns the LAN IP for a unique online local or directly linked peer domain, or an empty string if it is offline, unavailable, or ambiguous. `wifi.getDomainRecords()` lists known reservations, including offline records with `lanIP = ""` and `online = false`. Air lookups require a running bridge.
- `wifi.exportDomainToken(name)` returns a saved ownership token; `wifi.importDomainToken(name, token)` imports a backup. `wifi.releaseDomain(name)` releases a reservation on the connected router. `wifi.forgetDomainToken(name)` deletes only the local token, not the reservation.
- `wifi.send(recipient, message)` sends to a LAN IP or domain. `wifi.sendWAN(destination, lanIP, message)` sends directly by WAN address and LAN IP, or use `wifi.sendWAN(domain, nil, message)` for a registered domain.
- `wifi.receive(timeout)` receives queued LAN or WAN packets. `wifi.run()` runs the router bridge.

For air-connected clients, `wifi.send` and `wifi.sendWAN` wait for the bridge to accept or reject the request and return `false` with a reason if routing fails or the bridge does not respond. An accepted WAN send is queued for forwarding; it does not guarantee delivery.

Air-connected `wifi.registerDomain` also waits for the bridge to accept or reject the registration. Sends to a domain fail when the name is offline or ambiguous between local and peer records.

The first registration generates a random ownership token and saves it in `/.wifi-domains/tokens` on the computer before sending the request. The router stores only its hash, and the token is encrypted in transit even on an open LAN. The last active domain for each router is automatically restored when the computer reconnects. A computer can have one active domain per router; registering a different domain leaves the previous name reserved but offline.

Back up tokens privately with `wifi.exportDomainToken(name)`. To move ownership to another computer, import the token with `wifi.importDomainToken(name, token)`, connect to the original router, then call `wifi.registerDomain(name)` after the previous computer disconnects. Import refuses to overwrite a different saved token. Only a holder of the token can release the reservation using `wifi.releaseDomain(name)`; releasing does not delete the local token. `wifi.forgetDomainToken(name)` deletes the local copy without releasing the name. **If all copies of a token are lost, the reservation cannot be released or reclaimed through the API.**

Reservations survive disconnects and server restarts while the router block remains in the world. The router advertises offline reservations to currently linked peers, which prevents new claims there while the routers remain linked. Unlinked routers cannot coordinate: independent claims may conflict when links are restored, in which case resolution fails until the owners resolve the conflict. Each router can retain up to 254 reservations. Existing temporary registrations must be registered again to receive tokens.

An air-connected client can send to a local LAN address or domain with `wifi.send(lanIPOrDomain, message)`. If no local client matches and the recipient is a registered domain announced by a linked router, the message is routed over the WAN; for example, `wifi.send("storage.example", { message = "hello" })` sends a table payload. Explicit WAN sends with `wifi.send(domain, nil, message)` (or `wifi.sendWAN(domain, nil, message)`) are also supported. Use `wifi.receive()` for both: it returns the next LAN or WAN packet, with `from`, `fromLan`, `fromWan`, `domain`, and `message` fields. WAN reception is automatic; keep the destination router's bridge running so it can receive and decrypt WAN packets for the air client.

### Server configuration

NeoForge creates a per-world server config with these options:

- `wirelessRange` (default `60` blocks): scan radius for attached-peripheral nearby-router discovery. Air connections and discovery use the available CC:Tweaked modem/rednet links; no GPS setup is required.
- `maxClients` (default `155`): maximum LAN clients per router.
- `maxInboxSize` (default `64`): queued messages retained per client.
- `announceIntervalSeconds` (default `3`) and `peerTimeoutSeconds` (default `12`): WAN bridge discovery and peer expiry timing. Keep the peer timeout longer than the announcement interval.

Password-protected wireless LAN packets are encrypted and authenticated; open networks authenticate packets but do not encrypt them. The password is authenticated with a challenge response and is never sent over rednet in plaintext. Air reach depends on the CC:Tweaked modem/rednet setup.

### Linking routers

Configure the same strong, randomly generated WAN secret (16-128 characters) on each trusted router from an attached computer. WAN linking is disabled until a secret is configured:

```lua
local wifi = require("wifi")
assert(wifi.configureWan("replace-with-a-long-random-secret"))
```

An empty secret disables WAN linking. Changing the secret clears peer routes and queued WAN packets. The secret is saved in the router's world data, so protect world files and attached computers.

Run the module on one CC computer attached to each router. Each bridge computer needs a CC:Tweaked modem:

```lua
local wifi = require("wifi")
wifi.run()
```

`wifi.run()` opens all attached CC modems, discovers other routers, authenticates the WAN link using the shared secret, and forwards WAN packets. Router LAN SSIDs and passwords do not need to match. A wireless CC modem links routers over air; wired CC modems linked by cable work the same way. Keep the bridge program running. To run it alongside another program, use `parallel.waitForAny(wifi.run, yourProgram)`.

Register a domain on the destination computer:

```lua
local wifi = require("wifi")
assert(wifi.registerDomain("storage.example"))
```

The destination router's bridge advertises that name to authenticated WAN peers. Send to that computer from a wired or air-connected client with:

```lua
local wifi = require("wifi")
wifi.sendWAN("storage.example", nil, { message = "hello" })
```

The second argument must be `nil` when the first argument is a domain. Domains must be unique across linked routers. Router bridges currently advertise domains to their authenticated rednet peers; WAN sends still require a direct peer route.

Messages may be strings, numbers, booleans, or serializable Lua tables (including nested tables); nil, functions, userdata, and cyclic tables are rejected. WAN messages are limited to 64 KiB, 64 levels of nesting, and 65,536 table entries. The receiving computer reads the packet with `receive()`. WAN packets raise `wifi_message`; a failed route raises `wifi_wan_unreachable`. Since LAN IPs can repeat across routers, send to a remote computer by its domain rather than by the IP returned by `wifi.resolve`.

Messages raise a `wifi_message` event and connections raise `wifi_connected` / `wifi_disconnected` events. Client sessions are held in memory and end on disconnect; domain reservations are saved with the router. Conflicting domain reservations are treated as ambiguous rather than routed. LAN credentials and the WAN secret are saved with the router block. Only trust computers attached to the router and WAN routers that know its secret; anyone with access to a computer's token files can use its domain ownership.

Password-protected wireless packets use AES-256-GCM with random server-held session keys; open networks use authenticated but readable packets. WAN data and domain announcements use AES-256-GCM with a random server key bound to the shared WAN secret. Session and server keys are not saved or sent over rednet, so old packets cannot be decrypted after restart. Domain announcements are authenticated and replay-checked, and a domain-addressed WAN packet is rejected if the destination no longer owns that name. WAN data packets themselves can be replayed while the server is running. Trusted routers can still advertise conflicting names. Public discovery and link-authentication packets reveal SSIDs and router addresses; rednet also exposes traffic timing and approximate packet size. Captured login proofs may permit offline password guessing, so use strong LAN passwords and WAN secrets.

This is an in-game network, not a connection to real Wi-Fi or the internet. All routers and clients must use the updated mod and module.

