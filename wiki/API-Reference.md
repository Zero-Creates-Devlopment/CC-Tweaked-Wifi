# `wifi` API reference

```lua
local wifi = require("wifi")
```

The module is available in CC:Tweaked's ROM. Functions taking `routerName` accept an optional peripheral name; otherwise the module finds an attached router or, where supported, uses the active wireless connection. Configuration, client listing, static IP assignment, network configuration, and the bridge require an attached router.

## Connections and discovery

### `wifi.connect(ssid, password [, routerName])`

Connects to an attached router or discovers a router over the air. Air connections require a wireless modem and a running router bridge. SSIDs are 1-32 characters; passwords are empty or 8-63 characters.

Returns `true` on connection, optionally with a warning as the second return value if saved domain restoration failed. Returns `false, reason` on failure.

### `wifi.disconnect([routerName])`

Disconnects the computer. Returns a boolean and may return an error reason.

### `wifi.listNearbyRouters([routerName [, duration]])`

Returns an array of router records or `nil, reason`. Records contain `ssid`, `wanIP`, `security` (`"open"` or `"password"`), `x`, `y`, `z`, and `connectedClients`.

With an attached router, scans loaded chunks within `wirelessRange` and includes `distance`. Without one, listens for bridge beacons over a wireless modem and includes the bridge's `computerID`, but not distance. Air discovery defaults to 3.5 seconds; an explicit duration must be greater than 0 and at most 60 seconds. To specify only a duration, use `wifi.listNearbyRouters(nil, 5)`.

### `wifi.getStatus([routerName])`

Returns a table with `connected`, `ssid`, `wanIP`, and `lanIP` when connected. A disconnected wireless client gets `{ connected = false }`; an attached but unconnected client has `lanIP = ""`. Attached-router status also includes `security`, `connectedClients`, `range`, and `maxClients`. Test `status.connected`, not the truthiness of the table.

### `wifi.getIP([routerName])` and `wifi.getWanIP([routerName])`

Return the current LAN and router WAN addresses. `getIP` returns `nil` for a disconnected wireless client and `""` for an attached but unconnected client. `getWanIP` returns `nil` without an attached router or active connection. A WAN address identifies a dimension and router coordinates, for example `minecraft:overworld.12.64.-30`.

## Domains

Domains are normalized to lowercase. Names use DNS-like labels of letters, digits and hyphens, 1-63 characters per label and at most 253 characters in total. Each label must start and end with a letter or digit; underscores are not allowed.

### `wifi.registerDomain(name [, routerName])`

Reserves and activates a name for the connected computer, returning `true` or `false, reason`. A successful registration can also return a warning if saving the active selection failed. The ownership token is saved locally, and the active domain is normally restored on reconnect. Each computer has one active domain per router; switching names leaves previous reservations offline.

### `wifi.resolve(name [, routerName])`

Returns the LAN IP of a unique online local or directly linked peer domain. Returns `""` when the name is offline, unavailable, or ambiguous; wireless lookup errors may return `nil, reason`. LAN IPs can repeat on different routers, so use the domain rather than the result as a remote send destination.

### `wifi.getDomainRecords([routerName])`

Returns an array of records `{ domain, lanIP, wanIP, online }`, or `nil, reason`. Offline records have `lanIP = ""` and `online = false`. Wireless lookups require a running bridge.

### `wifi.exportDomainToken(name)`

Returns the locally saved token, or `nil, reason`. Store it privately.

### `wifi.importDomainToken(name, token)`

Saves a token for recovery or transfer. Returns `true` or `false, reason`; it refuses to overwrite a different token. Connect to the original router and call `registerDomain` to activate it.

### `wifi.releaseDomain(name [, routerName])`

Releases a reservation on the connected router using the saved token. Returns `true` or `false, reason`; the local token is not deleted.

### `wifi.forgetDomainToken(name)`

Deletes the local token only; it does not release the reservation. Returns `true` or `false, reason`. If all copies are lost, the reservation cannot be reclaimed or released via the API.

## Messaging

### `wifi.send(recipient, message)`

Sends to a local LAN IP or a unique online domain, including one announced by a directly linked router. Returns a boolean and may return a reason. Self-sends are rejected.

### `wifi.sendWAN(destination, lanIP, message [, routerName])`

Sends to a WAN address and its LAN IP, or to a domain using `wifi.sendWAN(domain, nil, message)`. The equivalent `wifi.send(destination, lanIP, message [, routerName])` form is also supported. WAN forwarding requires a running bridge and a direct peer route. An accepted WAN send is queued, not a delivery guarantee.

Messages can be strings, numbers, booleans, or serializable non-cyclic tables. WAN payloads are limited to 64 KiB, 64 levels of nesting, and 65,536 table entries.

### `wifi.receive([timeout [, routerName]])`

Returns the next queued packet, or `nil` on timeout; failures may also return a reason. Packet fields are `from` and `fromLan` (source LAN IP), `fromWan` (source router WAN address), `domain` (source domain or `""`), and `message`. There is no timestamp. Use both `fromWan` and `fromLan` to reply across routers.

For attached routers, omitted or zero timeout polls immediately. For wireless clients, an omitted timeout waits, while zero currently returns immediately without polling. A positive timeout works for either connection. The inbox retains messages until read or displaced by newer messages when full.

## Attached-router operations

### `wifi.getClients([routerName])`

Returns an array of `{ computerId, ip, lanIP, domain, connectionType }` records, or `nil, reason`. `ip` and `lanIP` contain the same address; `connectionType` is `"wired"` or `"wireless"`.

### `wifi.setStaticIP(address [, routerName])`

Assigns an unused address from `192.168.1.1` through `192.168.1.254` to the connected attached computer. Returns a boolean; wireless-only clients cannot use this operation.

### `wifi.getNetworkConfig([routerName])`

Returns the five network settings described in [Configuration](Configuration), or `nil, reason`.

### `wifi.configureWifi(ssid, password [, routerName])`

Sets LAN credentials. The SSID must be nonblank and at most 32 characters; the password must be empty or 8-63 characters. Changing credentials disconnects clients.

### `wifi.configureWan(secret [, routerName])`

Sets a shared WAN secret of 16-128 characters, or an empty string to disable WAN linking. Changing it clears peer routes and queued WAN packets.

### `wifi.run([routerName])`

Runs the bridge loop on an attached computer with at least one wired or wireless modem. Only one computer can bridge a router at a time. Keep it running for wireless clients and WAN forwarding.
