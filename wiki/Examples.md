# Examples

The global `wifi` API is available automatically after computer startup; `require("wifi")` is optional. Configure a router and run its bridge as shown in [Quick Start](Quick-Start). Replace example credentials with your own.

## Discover and connect

On a computer with a wireless modem:

```lua
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
local ok, reason = wifi.send("storage.example", { request = "inventory" })
assert(ok, reason)
```

Both computers must already be connected. The domain may belong to any loaded router on the server. A successful send places the message in the destination inbox.

## WAN broadcast and conversation threads

Send to a router's WAN address to reach all its connected clients:

```lua
local ok, thread = wifi.send("22.145.56.55", "hello")
assert(ok, thread)
local reply = wifi.receive(thread, 5)
if reply then print(reply.message) end
```

Each recipient can receive the first message without a filter and reuse its token:

```lua
local packet, reason = wifi.receive(5)
if reason then error(reason) end
if packet and packet.thread then
    local ok, result = wifi.send(packet.fromWan, "ack", packet.thread)
    assert(ok, result)
end
```

Replies to a WAN address are also broadcasts. The snippets use the automatically available global `wifi` and assume the computer is connected. Wireless clients need their local bridge running.

## Inspect reservations

```lua
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
local token, reason = wifi.exportDomainToken("storage.example")
assert(token, reason)
-- Copy token to a private backup using your chosen secure method.
```

On the replacement computer, after the previous owner disconnects, obtain that private backup and run:

```lua
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
