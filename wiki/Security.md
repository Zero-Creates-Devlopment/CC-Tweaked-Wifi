# Security

## Wireless LAN

Password-protected wireless packets use AES-256-GCM with random server-held session keys. Passwords are checked by challenge response rather than sent as plaintext over rednet. Captured login proofs may still allow offline password guessing, so choose a strong password.

Open LAN traffic is authenticated but readable. Sensitive ownership-token requests are encrypted even on an open LAN. Discovery and link-control traffic reveal metadata such as SSIDs and router addresses; timing and approximate packet sizes are also visible.

## WAN

WAN linking is disabled until each router is configured with the same shared secret. Use a strong random secret of 16-128 characters and share it only with trusted router operators.

WAN data and domain announcements use AES-256-GCM with a random server-held key bound to the shared secret. The key changes when the server restarts. Domain announcements are replay-checked, but **WAN data packets can be replayed while the server is running**. An accepted WAN send is queued, not guaranteed delivered.

Bridges communicate with direct authenticated rednet peers, not through an automatic mesh. A trusted peer can still advertise conflicting domain names. Ambiguous names cannot be resolved or used for domain-addressed sends until the conflict is resolved.

## Domain ownership

`wifi.registerDomain(name)` generates and saves a random ownership token on first registration. The router stores only a SHA-256 hash of the token. Reservations survive disconnects and server restarts while the router block remains in the world. The router advertises offline reservations to currently linked peers, but disconnected routers cannot coordinate independent claims.

The token is saved under `/.wifi-domains/tokens` on the computer. **Anyone with the token can reclaim or release the domain.** Back it up privately with `wifi.exportDomainToken(name)`. To transfer or recover ownership, import it on another computer with `wifi.importDomainToken(name, token)`, connect to the original router, and register the domain after the previous owner disconnects. Import refuses to overwrite a different saved token.

`wifi.releaseDomain(name)` releases the reservation but keeps the local token. `wifi.forgetDomainToken(name)` deletes only the local copy, not the reservation. If every copy is lost, the reservation cannot be reclaimed or released through the API.

## Trust boundaries

LAN credentials and the WAN secret are stored in router world data. Protect world files, attached computers, and token backups. Trust computers attached to routers, token holders, and holders of the WAN secret; the mod does not authenticate individual user accounts.
