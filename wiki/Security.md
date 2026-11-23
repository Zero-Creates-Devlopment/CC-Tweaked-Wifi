# Security

## Wireless LAN

Password-protected wireless packets use AES-256-GCM with random server-held session keys. Passwords are checked by challenge response rather than sent as plaintext over rednet. Captured login proofs may still allow offline password guessing, so choose a strong password.

Open LAN traffic is authenticated but readable. Sensitive ownership-token requests are encrypted even on an open LAN. Discovery and link-control traffic reveal metadata such as SSIDs and router addresses; timing and approximate packet sizes are also visible.

## Server-wide routing

Loaded routers exchange domain records and messages internally on the server, including across dimensions. This traffic is not transmitted over rednet and does not use a WAN secret or WAN packet encryption. A successful send places the message in the recipient's inbox, but does not guarantee that the application reads it.

Ambiguous names cannot be resolved or used for domain-addressed sends until the conflict is resolved. A domain-addressed message is rejected if its destination no longer owns the name.

## Domain ownership

`wifi.registerDomain(name)` generates and saves a random ownership token on first registration. The router stores only a SHA-256 hash of the token. Reservations survive disconnects and server restarts while the router block remains in the world. Loaded routers see each other's offline reservations and prevent conflicting new claims. Reservations on unloaded routers are not visible; independent claims can conflict when those routers load.

The token is saved under `/.wifi-domains/tokens` on the computer. **Anyone with the token can reclaim or release the domain.** Back it up privately with `wifi.exportDomainToken(name)`. To transfer or recover ownership, import it on another computer with `wifi.importDomainToken(name, token)`, connect to the original router, and register the domain after the previous owner disconnects. Import refuses to overwrite a different saved token.

`wifi.releaseDomain(name)` releases the reservation but keeps the local token. `wifi.forgetDomainToken(name)` deletes only the local copy, not the reservation. If every copy is lost, the reservation cannot be reclaimed or released through the API.

## Trust boundaries

LAN credentials are stored in router world data. Protect world files, attached computers, and token backups. Trust computers attached to routers and token holders; the mod does not authenticate individual user accounts.
