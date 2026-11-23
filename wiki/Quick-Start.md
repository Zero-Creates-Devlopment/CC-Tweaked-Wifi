# Quick Start

## 1. Craft and place a router

Craft the router using this pattern:

| Iron ingot | Nether quartz | Iron ingot |
|---|---|---|
| Redstone dust | Glass | Redstone dust |
| Iron ingot | Iron ingot | Iron ingot |

Place the router and attach a CC:Tweaked computer so it can access the router peripheral.

## 2. Configure and run the bridge

On the attached computer, start Lua and configure the LAN:

```lua
local wifi = require("wifi")
assert(wifi.configureWifi("WorkshopNet", "change-me-123"))
```

Replace the example password with a strong one. An empty password creates an open network. Changing credentials disconnects clients.

Attach a CC:Tweaked modem to the same computer, then run:

```lua
wifi.run()
```

Keep this program running. This same computer can configure and bridge the router; a separate configuration computer is not required. A wireless modem is needed on the bridge for wireless clients.

## 3. Connect a wireless client

Give another computer a wireless modem and run:

```lua
local wifi = require("wifi")
local ok, warning = wifi.connect("WorkshopNet", "change-me-123")
assert(ok, warning)
if warning then print(warning) end
print("LAN IP:", wifi.getIP())
print(textutils.serialize(wifi.getStatus()))
```

Discovery can be inspected with `wifi.listNearbyRouters()`. Air discovery uses bridge beacons and CC:Tweaked modem/rednet reach; it does not use GPS or the `wirelessRange` scan setting.

## 4. Reserve a domain

On a connected client:

```lua
local ok, warning = wifi.registerDomain("storage.example")
assert(ok, warning)
if warning then print(warning) end
local token, reason = wifi.exportDomainToken("storage.example")
assert(token, reason)
-- Store the token privately and separately as a backup.
```

The token is saved automatically on the computer. Anyone with it can claim or release the reservation, so protect backups. If every copy is lost, the reservation cannot be reclaimed or released through the API. See [Security](Security) for recovery and limitations.

## 5. Use other routers

Loaded routers automatically share domain records and route messages server-wide, even across dimensions. No link, WAN secret, or modem connection between routers is required. Router chunks are not force-loaded. Wireless clients still need a running local bridge. See [Examples](Examples) for messaging.
