# Troubleshooting

## The mod or router is missing

Check Minecraft, NeoForge, Java, and CC:Tweaked versions against [Installation](Installation). Confirm both mod jars are in the correct instance's `mods` folder and inspect the launcher or server logs. The router is in the existing Functional Blocks creative tab.

## No router appears or a wireless connection fails

- Check that the client has a wireless modem and that an attached bridge computer is running `wifi.run()` with a wireless modem.
- Check the SSID and password and the router's client limit. `No matching router responded` can have several causes, including reach and authentication; it does not identify a single failure.
- Check CC:Tweaked modem/rednet connectivity and whether the relevant computers and chunks are active. Air discovery listens for bridge beacons and does not use GPS or `wirelessRange`.
- On an attached computer, `wifi.listNearbyRouters()` instead scans loaded chunks within `wirelessRange`; it does not load chunks.
- A credential change disconnects clients; reconnect with the new credentials.

## The bridge does not start

`wifi.run()` needs an attached router and at least one CC:Tweaked modem. Only one computer can bridge a router at a time. Check the bridge computer's error output and keep the program running.

## Routers do not link or WAN messages do not arrive

- Configure the same nonempty 16-128-character WAN secret on both attached routers and run both bridges.
- Check their modem/rednet connection. WAN routes require direct peers; a chain of routers does not automatically relay messages.
- Allow time for announcements, and keep `peerTimeoutSeconds` above `announceIntervalSeconds`.
- An accepted WAN send is queued, not guaranteed delivered. Check bridge errors and `wifi_wan_unreachable` events.
- Verify the destination is connected. Use a unique online domain or both the remote WAN address and LAN IP; LAN IPs can repeat on different routers.

## A domain cannot be registered or resolved

Use DNS-like labels containing letters, digits, or hyphens, not underscores; each label must start and end with a letter or digit. Names are normalized to lowercase. Verify the computer is connected and that the name is not already reserved without your token. Routers retain at most 254 reservations.

An offline reservation has an empty LAN IP. Independent claims made while routers are unlinked can conflict when they link; ambiguous names are not routed. Owners must resolve the conflict, for example by releasing a reservation with its token.

After reconnecting, check the second result from `wifi.connect`: the connection may succeed while domain restoration fails. A token can be transferred with `exportDomainToken` and `importDomainToken`, then registered on the original router after the previous owner disconnects. If all token copies are lost, the reservation cannot be reclaimed or released through the API.

## Messages are missing

Check send results and bridge output. The destination must be connected, but it does not need to be actively listening while a message is queued. A full inbox discards the oldest message. Self-sends are rejected.

Use a positive timeout such as `wifi.receive(5)` for either connection type. An omitted timeout polls immediately on an attached router but waits on a wireless client; `wifi.receive(0)` does not reliably poll wireless messages.
