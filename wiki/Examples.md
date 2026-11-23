# Examples

These examples use `require("wifi")`. Configure a router and run its bridge as shown in [Quick Start](Quick-Start). Replace example credentials with your own.

## Discover and connect

On a computer with a wireless modem:

```lua
local wifi = require("wifi")
local routers, reason = wifi.listNearbyRouters(nil, 5)
assert(routers, reason)
for _, router in ipairs(routers) do
    print(router.ssid, router.wanIP, router.security)
end

local ok, warning = wifi.connect("WorkshopNet", "change-me-123")
assert(ok, warning)
if warning then print(warning) end
print("Connected:", wifi.getStatus().connected)
```

## Send and receive

On one connected computer, reserve a name:

```lua
local wifi = require("wifi")
local ok, warning = wifi.registerDomain("storage.example")
assert(ok, warning)
if warning then print(warning) end

while true do
    local packet, reason = wifi.receive(5)
    if reason then error(reason) end
    if packet then
        print("From:", packet.fromWan, packet.fromLan)
        print(textutils.serialize(packet.message))
    end
end
```

On a different connected computer:

```lua
local wifi = require("wifi")
local ok, reason = wifi.send("storage.example", { request = "inventory" })
assert(ok, reason)
```

Both computers must already be connected. The domain may belong to any loaded router on the server. A successful send places the message in the destination inbox.

## Reply using the source router

LAN addresses can repeat across routers. After receiving a packet, reply locally by LAN IP or remotely using both source addresses:

```lua
local packet, reason = wifi.receive(5)
if reason then error(reason) end
if packet then
    local ok, sendReason
    if packet.fromWan == wifi.getWanIP() then
        ok, sendReason = wifi.send(packet.fromLan, "ack")
    else
        ok, sendReason = wifi.sendWAN(packet.fromWan, packet.fromLan, "ack")
    end
    assert(ok, sendReason)
end
```

This snippet assumes `wifi` is already required and connected. Wireless clients need their local bridge running.

## Inspect reservations

```lua
local wifi = require("wifi")
local records, reason = wifi.getDomainRecords()
assert(records, reason)
for _, record in ipairs(records) do
    print(record.domain, record.wanIP, record.lanIP, record.online)
end
```

Offline reservations have an empty LAN IP. Names claimed while another router is unloaded can become ambiguous when it loads.

## Back up and recover ownership

On the owner computer, export the token and save it privately outside the computer. Do not broadcast or publish it:

```lua
local wifi = require("wifi")
local token, reason = wifi.exportDomainToken("storage.example")
assert(token, reason)
-- Copy token to a private backup using your chosen secure method.
```

On the replacement computer, after the previous owner disconnects, obtain that private backup and run:

```lua
local wifi = require("wifi")
local token = "paste-your-private-token-here"
local ok, reason = wifi.importDomainToken("storage.example", token)
assert(ok, reason)
ok, reason = wifi.connect("WorkshopNet", "change-me-123")
assert(ok, reason)
if reason then print(reason) end
ok, reason = wifi.registerDomain("storage.example")
assert(ok, reason)
if reason then print(reason) end
```

Connect to the original router. Importing the token does not itself activate the name.
