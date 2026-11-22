-- Wi-Fi router client and bridge APIs.
local M = {}

local PROTOCOL = "wifi_router_v1"
local CLIENT_PROTOCOL = "wifi_client_v1"
local activeConnection

local function getRouter(name)
    if type(name) == "table" then
        return name
    end
    if name then
        return peripheral.wrap(name)
    end
    return peripheral.find("wifi_router")
end

local function validWan(address)
    if type(address) ~= "string" then
        return false
    end
    local dimension, x, y, z = address:match("^(.+)%.(-?%d+)%.(-?%d+)%.(-?%d+)$")
    return dimension ~= nil and x ~= nil and y ~= nil and z ~= nil
end

local function validLan(address)
    if type(address) ~= "string" then
        return false
    end
    local host = address:match("^192%.168%.1%.(%d+)$")
    host = tonumber(host)
    return host ~= nil and host >= 1 and host <= 254
end

local function yieldExecution()
    os.queueEvent("wifi_module_yield")
    os.pullEvent("wifi_module_yield")
end

local MAX_MESSAGE_BYTES = 65536
local MAX_MESSAGE_ENTRIES = 65536
local MAX_MESSAGE_DEPTH = 64

local function validMessage(value, state, depth)
    state = state or { seen = {}, bytes = 0, entries = 0 }
    depth = depth or 0
    if depth > MAX_MESSAGE_DEPTH then
        return false
    end

    local kind = type(value)
    if kind == "string" then
        state.bytes = state.bytes + #value + 5
        return state.bytes <= MAX_MESSAGE_BYTES
    elseif kind == "number" then
        if value ~= value or value == math.huge or value == -math.huge then
            return false
        end
        state.bytes = state.bytes + 9
        return state.bytes <= MAX_MESSAGE_BYTES
    elseif kind == "boolean" then
        state.bytes = state.bytes + 2
        return state.bytes <= MAX_MESSAGE_BYTES
    end

    if kind ~= "table" then
        return false
    end
    if state.seen[value] then
        return false
    end
    state.seen[value] = true
    state.bytes = state.bytes + 5
    for key, item in pairs(value) do
        local keyType = type(key)
        if keyType ~= "string" and keyType ~= "number" and keyType ~= "boolean" then
            state.seen[value] = nil
            return false
        end
        if keyType == "string" then
            state.bytes = state.bytes + #key + 5
        elseif keyType == "number" then
            if key ~= key or key == math.huge or key == -math.huge then
                state.seen[value] = nil
                return false
            end
            state.bytes = state.bytes + 9
        else
            state.bytes = state.bytes + 2
        end
        state.entries = state.entries + 1
        if state.entries % 128 == 0 then
            yieldExecution()
        end
        if state.entries > MAX_MESSAGE_ENTRIES or state.bytes > MAX_MESSAGE_BYTES
                or not validMessage(item, state, depth + 1) then
            state.seen[value] = nil
            return false
        end
    end
    state.seen[value] = nil
    return state.bytes <= MAX_MESSAGE_BYTES
end

local function newChallenge()
    return table.concat({
        tostring(os.epoch("utc")),
        tostring(os.getComputerID()),
        tostring(math.random(0, 2147483647)),
    }, ":")
end

local SHA256_K = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
}

local function add32(...)
    local sum = 0
    for i = 1, select("#", ...) do
        sum = sum + select(i, ...)
    end
    return sum % 0x100000000
end

local function word32(value)
    return string.char(
        bit32.band(bit32.rshift(value, 24), 0xff),
        bit32.band(bit32.rshift(value, 16), 0xff),
        bit32.band(bit32.rshift(value, 8), 0xff),
        bit32.band(value, 0xff)
    )
end

local function sha256(message)
    local length = #message
    local bitLength = length * 8
    local padLength = (56 - (length + 1) % 64) % 64
    message = message .. string.char(0x80) .. string.rep("\0", padLength)
        .. word32(math.floor(bitLength / 0x100000000)) .. word32(bitLength % 0x100000000)

    local hash = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    }

    for offset = 1, #message, 64 do
        local words = {}
        for i = 0, 15 do
            local a, b, c, d = message:byte(offset + i * 4, offset + i * 4 + 3)
            words[i + 1] = ((a * 256 + b) * 256 + c) * 256 + d
        end
        for i = 17, 64 do
            local x = words[i - 15]
            local y = words[i - 2]
            local s0 = bit32.bxor(bit32.rrotate(x, 7), bit32.rrotate(x, 18), bit32.rshift(x, 3))
            local s1 = bit32.bxor(bit32.rrotate(y, 17), bit32.rrotate(y, 19), bit32.rshift(y, 10))
            words[i] = add32(words[i - 16], s0, words[i - 7], s1)
            if i % 8 == 0 then
                yieldExecution()
            end
        end

        local a, b, c, d, e, f, g, h = table.unpack(hash)
        for i = 1, 64 do
            local s1 = bit32.bxor(bit32.rrotate(e, 6), bit32.rrotate(e, 11), bit32.rrotate(e, 25))
            local choose = bit32.bxor(bit32.band(e, f), bit32.band(bit32.bnot(e), g))
            local t1 = add32(h, s1, choose, SHA256_K[i], words[i])
            local s0 = bit32.bxor(bit32.rrotate(a, 2), bit32.rrotate(a, 13), bit32.rrotate(a, 22))
            local majority = bit32.bxor(bit32.band(a, b), bit32.band(a, c), bit32.band(b, c))
            local t2 = add32(s0, majority)
            h, g, f, e, d, c, b, a = g, f, e, add32(d, t1), c, b, a, add32(t1, t2)
            if i % 8 == 0 then
                yieldExecution()
            end
        end
        hash[1] = add32(hash[1], a)
        hash[2] = add32(hash[2], b)
        hash[3] = add32(hash[3], c)
        hash[4] = add32(hash[4], d)
        hash[5] = add32(hash[5], e)
        hash[6] = add32(hash[6], f)
        hash[7] = add32(hash[7], g)
        hash[8] = add32(hash[8], h)
    end

    local digest = {}
    for i = 1, 8 do
        digest[i] = word32(hash[i])
    end
    return table.concat(digest)
end

local function hmacSha256(key, message)
    if #key > 64 then
        key = sha256(key)
    end
    key = key .. string.rep("\0", 64 - #key)
    local inner, outer = {}, {}
    for i = 1, 64 do
        local byte = key:byte(i)
        inner[i] = string.char(bit32.bxor(byte, 0x36))
        outer[i] = string.char(bit32.bxor(byte, 0x5c))
    end
    return sha256(table.concat(outer) .. sha256(table.concat(inner) .. message))
end

local function toHex(value)
    return (value:gsub(".", function(byte)
        return string.format("%02x", byte:byte())
    end))
end

local function openRednet()
    local opened = {}
    local count = 0
    for _, name in ipairs(peripheral.getNames()) do
        if peripheral.getType(name) == "modem" then
            if not rednet.isOpen(name) then
                rednet.open(name)
                opened[#opened + 1] = name
            end
            count = count + 1
        end
    end
    return opened, count
end

local function closeRednet(opened)
    for _, name in ipairs(opened) do
        rednet.close(name)
    end
end

local function sendAirPacket(connection, packet, sensitive)
    local seal = sensitive and wifi_air_crypto.sealSensitive or wifi_air_crypto.seal
    local plaintext
    if sensitive then
        plaintext = table.concat({ packet.kind, packet.domain, packet.token, packet.requestId }, "\0")
    else
        plaintext = textutils.serialize(packet)
    end
    local ok, protected = pcall(seal, connection.wanIP, plaintext)
    if not ok then
        return false, tostring(protected)
    end
    rednet.send(connection.routerComputerID, {
        kind = sensitive and "secure_sensitive" or "secure",
        ciphertext = protected,
    }, CLIENT_PROTOCOL)
    return true
end

local function openAirPacket(wanIP, packet)
    if type(packet) ~= "table" or packet.kind ~= "secure" or type(packet.ciphertext) ~= "string" then
        return nil
    end
    local ok, plaintext = pcall(wifi_air_crypto.open, wanIP, packet.ciphertext)
    if not ok then
        printError("wifi: rejected wireless packet: " .. tostring(plaintext))
        return nil
    end
    local parsed, result = pcall(textutils.unserialize, plaintext)
    if not parsed or type(result) ~= "table" then
        printError("wifi: rejected malformed wireless packet")
        return nil
    end
    return result
end

local function sendAirRequest(packet, sensitive)
    local opened, count = openRednet()
    if count == 0 then
        return false, "A wireless modem is required"
    end
    local requestId = newChallenge()
    local routerComputerID = activeConnection.routerComputerID
    packet.requestId = requestId
    local sent, reason = sendAirPacket(activeConnection, packet, sensitive)
    if not sent then
        closeRednet(opened)
        return false, reason
    end
    local deadline = os.epoch("utc") + 5000
    while os.epoch("utc") < deadline do
        local senderID, response = rednet.receive(CLIENT_PROTOCOL, math.max(0, (deadline - os.epoch("utc")) / 1000))
        if not senderID then
            break
        end
        if senderID == routerComputerID then
            response = openAirPacket(activeConnection.wanIP, response)
        end
        if senderID == routerComputerID and type(response) == "table" and response.kind == "send_result"
                and response.requestId == requestId and type(response.success) == "boolean" then
            closeRednet(opened)
            return response.success, response.reason
        end
    end
    closeRednet(opened)
    return false, "Router did not respond to request"
end

local TOKEN_DIRECTORY = "/.wifi-domains/tokens"
local ROUTER_DIRECTORY = "/.wifi-domains/routers"

local function normalizeDomain(domain)
    if type(domain) ~= "string" or #domain == 0 or #domain > 253 then
        return nil
    end
    domain = domain:lower()
    if domain:sub(1, 1) == "." or domain:sub(-1) == "." or domain:find("..", 1, true) then
        return nil
    end
    for label in domain:gmatch("[^.]+") do
        if #label > 63 or not label:match("^[a-z0-9][a-z0-9-]*$")
                or not label:sub(-1):match("[a-z0-9]") then
            return nil
        end
    end
    return domain
end

local function validToken(token)
    return type(token) == "string" and #token == 64 and token:match("^[0-9a-f]+$") ~= nil
end

local function readFile(path)
    if not fs.exists(path) then
        return nil
    end
    local file, reason = fs.open(path, "r")
    if not file then
        return nil, reason or "Could not read " .. path
    end
    local value = file.readAll()
    file.close()
    return value
end

local function writeFile(path, value)
    local file, reason = fs.open(path, "w")
    if not file then
        return false, reason or "Could not write " .. path
    end
    file.write(value)
    file.close()
    return true
end

local function tokenPath(domain)
    return TOKEN_DIRECTORY .. "/" .. domain
end

local function activePath(wan)
    return ROUTER_DIRECTORY .. "/" .. toHex(sha256(wan))
end

local function readToken(domain)
    local token, reason = readFile(tokenPath(domain))
    if reason then return nil, reason end
    if token and not validToken(token) then
        return nil, "Saved domain token is invalid"
    end
    return token
end

local function saveToken(domain, token)
    local existing, reason = readToken(domain)
    if reason then return false, reason end
    if existing then
        if existing ~= token then
            return false, "A different token is already saved for this domain"
        end
        return true
    end
    fs.makeDir(TOKEN_DIRECTORY)
    return writeFile(tokenPath(domain), token)
end

local function restoreDomain(wan, routerName)
    local domain, reason = readFile(activePath(wan))
    if reason then return reason end
    if domain then
        if not normalizeDomain(domain) then
            return "Saved active domain is invalid"
        end
        local token, tokenReason = readToken(domain)
        if not token then return tokenReason or "Saved active domain token is missing" end
        local restored, failure = M.registerDomain(domain, routerName)
        if not restored then return failure or "Domain restoration was rejected" end
        if failure then return failure end
    end
end

function M.getWanIP(routerName)
    local router = getRouter(routerName)
    if router then
        return router.getWanIP()
    end
    return activeConnection and activeConnection.wanIP or nil
end

function M.getIP(routerName)
    local router = getRouter(routerName)
    if router then
        return router.getIP()
    end
    return activeConnection and activeConnection.ip or nil
end

function M.getStatus(routerName)
    local router = getRouter(routerName)
    if router then
        local wifi = router.getWifi()
        wifi.lanIP = router.getIP()
        wifi.connected = wifi.lanIP ~= ""
        return wifi
    end
    if activeConnection then
        return {
            ssid = activeConnection.ssid,
            wanIP = activeConnection.wanIP,
            lanIP = activeConnection.ip,
            connected = true,
        }
    end
    return { connected = false, lanIP = nil, wanIP = nil }
end

function M.resolve(domain, routerName)
    local router = getRouter(routerName)
    if router then
        return router.resolve(domain)
    end
    if not activeConnection then
        return nil, "Not connected to a Wi-Fi router"
    end
    if type(domain) ~= "string" or #domain > 253 then
        return nil, "Domain must be a string of at most 253 characters"
    end
    local opened, count = openRednet()
    if count == 0 then
        return nil, "A wireless modem is required"
    end
    local requestId = newChallenge()
    local routerComputerID = activeConnection.routerComputerID
    local sent, reason = sendAirPacket(activeConnection, { kind = "resolve", domain = domain, requestId = requestId })
    if not sent then
        closeRednet(opened)
        return nil, reason
    end
    local deadline = os.epoch("utc") + 5000
    while os.epoch("utc") < deadline do
        local senderID, packet = rednet.receive(CLIENT_PROTOCOL, math.max(0, (deadline - os.epoch("utc")) / 1000))
        if not senderID then
            break
        end
        if senderID == routerComputerID then
            packet = openAirPacket(activeConnection.wanIP, packet)
        end
        if senderID == routerComputerID and type(packet) == "table" and packet.kind == "resolve_result"
                and packet.requestId == requestId and type(packet.lanIP) == "string" then
            closeRednet(opened)
            return packet.lanIP
        end
    end
    closeRednet(opened)
    return nil, "Domain lookup timed out"
end

function M.getClients(routerName)
    local router = getRouter(routerName)
    if router then
        return router.getClients()
    end
    return nil, "A router peripheral is required"
end

function M.getDomainRecords(routerName)
    local router = getRouter(routerName)
    if router then
        return router.getKnownDomainRecords()
    end
    if not activeConnection then
        return nil, "Not connected to a Wi-Fi router"
    end
    local opened, count = openRednet()
    if count == 0 then
        return nil, "A wireless modem is required"
    end
    local requestId = newChallenge()
    local routerComputerID = activeConnection.routerComputerID
    local sent, reason = sendAirPacket(activeConnection, { kind = "get_domains", requestId = requestId })
    if not sent then
        closeRednet(opened)
        return nil, reason
    end
    local deadline = os.epoch("utc") + 5000
    while os.epoch("utc") < deadline do
        local senderID, packet = rednet.receive(CLIENT_PROTOCOL, math.max(0, (deadline - os.epoch("utc")) / 1000))
        if not senderID then
            break
        end
        if senderID == routerComputerID then
            packet = openAirPacket(activeConnection.wanIP, packet)
        end
        if senderID == routerComputerID and type(packet) == "table" and packet.kind == "domains_result"
                and packet.requestId == requestId and type(packet.domains) == "table" then
            closeRednet(opened)
            return packet.domains
        end
    end
    closeRednet(opened)
    return nil, "Domain records lookup timed out"
end

function M.setStaticIP(address, routerName)
    local router = getRouter(routerName)
    if router then
        return router.setStaticIP(address)
    end
    return false, "A router peripheral is required"
end

function M.getNetworkConfig(routerName)
    local router = getRouter(routerName)
    if router then
        return router.getNetworkConfig()
    end
    return nil, "A router peripheral is required"
end

function M.send(...)
    local argumentCount = select("#", ...)
    if argumentCount == 2 then
        local recipient, message = ...
        if type(recipient) ~= "string" then
            return false, "Recipient must be an IP address or domain name"
        end
        if activeConnection then
            if not validMessage(message) then
                return false, "Message must be a serializable string, number, boolean, or table"
            end
            return sendAirRequest({
                kind = "send",
                recipient = recipient,
                message = message,
            })
        end
        local router = getRouter()
        if router then
            return router.send(recipient, message)
        end
        return false, "Not connected to a Wi-Fi router"
    end

    if argumentCount ~= 3 and argumentCount ~= 4 then
        return false, "Expected (recipient, message) or (WAN address/domain, LAN IP, message)"
    end
    local destinationWan, destinationLan, message, routerName = ...
    local router = getRouter(routerName)
    if router then
        return router.sendWAN(destinationWan, destinationLan, message)
    end
    if activeConnection then
        return M.sendWAN(destinationWan, destinationLan, message)
    end
    return false, "Not connected to a Wi-Fi router"
end

function M.receive(timeout, routerName)
    if timeout ~= nil and (type(timeout) ~= "number" or timeout < 0) then
        error("timeout must be a non-negative number", 2)
    end
    local router = getRouter(routerName)
    if router then
        local packet = router.receive()
        if packet or not timeout or timeout == 0 then
            return packet
        end
        local timer = os.startTimer(timeout)
        while true do
            local event, eventId = os.pullEvent()
            if event == "wifi_message" then
                packet = router.receive()
                if packet then
                    os.cancelTimer(timer)
                    return packet
                end
            elseif event == "timer" and eventId == timer then
                return router.receive()
            end
        end
    end
    if not activeConnection then
        return nil, "Not connected to a Wi-Fi router"
    end
    local opened, count = openRednet()
    if count == 0 then
        return nil, "A wired or wireless modem is required"
    end
    local deadline = timeout and (os.epoch("utc") + timeout * 1000) or nil
    while true do
        if deadline and os.epoch("utc") >= deadline then
            closeRednet(opened)
            return nil
        end
        local sent, reason = sendAirPacket(activeConnection, { kind = "receive" })
        if not sent then
            closeRednet(opened)
            return nil, reason
        end
        local pollTimeout = 0.25
        if deadline then
            pollTimeout = math.min(pollTimeout, math.max(0, (deadline - os.epoch("utc")) / 1000))
        end
        local senderID, packet = rednet.receive(CLIENT_PROTOCOL, pollTimeout)
        if senderID == activeConnection.routerComputerID then
            packet = openAirPacket(activeConnection.wanIP, packet)
        end
        if senderID == activeConnection.routerComputerID and type(packet) == "table"
                and packet.kind == "receive_result" and packet.available and type(packet.data) == "table" then
            closeRednet(opened)
            return packet.data
        elseif senderID == activeConnection.routerComputerID and type(packet) == "table"
                and packet.kind == "disconnected" then
            activeConnection = nil
            closeRednet(opened)
            return nil, "Disconnected from Wi-Fi router"
        end
    end
end

function M.connect(ssid, password, routerName)
    if type(ssid) ~= "string" or #ssid < 1 or #ssid > 32 then
        return false, "SSID must be 1-32 characters"
    end
    if type(password) ~= "string" or (#password ~= 0 and (#password < 8 or #password > 63)) then
        return false, "Password must be empty or 8-63 characters"
    end
    if activeConnection then
        local opened, count = openRednet()
        if count > 0 then
            local sent, reason = sendAirPacket(activeConnection, { kind = "disconnect" })
            if not sent then
                printError("wifi: could not disconnect previous session: " .. tostring(reason))
            end
            closeRednet(opened)
        end
        activeConnection = nil
    end
    local router = getRouter(routerName)
    if router then
        if not router.connect(ssid, password) then
            return false, "Connection failed: check credentials and router client limit"
        end
        local reason = restoreDomain(router.getWanIP(), routerName)
        if reason then printError("wifi: domain restoration failed: " .. reason) end
        return true, reason
    end
    local opened, count = openRednet()
    if count == 0 then
        return false, "A wireless modem is required for an air connection"
    end
    local clientNonce = newChallenge()
    local localComputerID = os.getComputerID()
    rednet.broadcast({
        kind = "join",
        ssid = ssid,
        clientNonce = clientNonce,
    }, CLIENT_PROTOCOL)

    local deadline = os.epoch("utc") + 8000
    local failure = "No matching router responded"
    local pending = {}
    while os.epoch("utc") < deadline do
        local senderID, packet = rednet.receive(CLIENT_PROTOCOL,
            math.max(0, (deadline - os.epoch("utc")) / 1000))
        if not senderID then
            break
        end
        if type(packet) == "table" and packet.clientNonce == clientNonce and packet.ssid == ssid then
            if packet.kind == "challenge" and type(packet.challenge) == "string"
                    and type(packet.wanIP) == "string" then
                local message = packet.challenge .. "\0" .. localComputerID .. "\0" .. clientNonce
                    .. "\0" .. packet.wanIP .. "\0" .. ssid
                local proof = toHex(hmacSha256(ssid .. "\0" .. password, message))
                rednet.send(senderID, {
                    kind = "proof",
                    clientNonce = clientNonce,
                    challenge = packet.challenge,
                    proof = proof,
                }, CLIENT_PROTOCOL)
                pending[senderID] = packet.wanIP
            end
        elseif type(packet) == "table" and packet.kind == "secure" and pending[senderID] then
            packet = openAirPacket(pending[senderID], packet)
            if packet and packet.kind == "join_result" and packet.clientNonce == clientNonce
                    and packet.ssid == ssid and packet.wanIP == pending[senderID]
                    and packet.routerComputerID == senderID then
                if packet.success and type(packet.ip) == "string" and type(packet.wanIP) == "string" then
                    activeConnection = {
                        ssid = ssid,
                        ip = packet.ip,
                        wanIP = packet.wanIP,
                        routerComputerID = senderID,
                    }
                    closeRednet(opened)
                    local reason = restoreDomain(activeConnection.wanIP)
                    if reason then printError("wifi: domain restoration failed: " .. reason) end
                    return true, reason
                end
                failure = packet.reason or "Router rejected the connection"
            end
        end
    end
    closeRednet(opened)
    return false, failure
end

function M.disconnect(routerName)
    local router = getRouter(routerName)
    if router then
        return router.disconnect()
    end
    if not activeConnection then
        return false
    end
    local opened, count = openRednet()
    if count == 0 then
        return false, "A wireless modem is required"
    end
    local sent, reason = sendAirPacket(activeConnection, { kind = "disconnect" })
    closeRednet(opened)
    activeConnection = nil
    if not sent then
        return false, reason
    end
    return true
end

function M.registerDomain(domain, routerName)
    domain = normalizeDomain(domain)
    if not domain then return false, "Invalid domain name" end
    local router = getRouter(routerName)
    if not router and not activeConnection then
        return false, "Not connected to a Wi-Fi router"
    end
    local wan = router and router.getWanIP() or activeConnection.wanIP
    local token, reason = readToken(domain)
    if reason then return false, reason end
    if not token then
        token = wifi_air_crypto.newToken()
        local saved
        saved, reason = saveToken(domain, token)
        if not saved then return false, reason end
    end
    local registered
    if router then
        registered = router.registerDomain(domain, token)
        if not registered then reason = "Domain registration was rejected" end
    else
        registered, reason = sendAirRequest({ kind = "register_domain", domain = domain, token = token }, true)
    end
    if not registered then return false, reason end
    fs.makeDir(ROUTER_DIRECTORY)
    local saved
    saved, reason = writeFile(activePath(wan), domain)
    if not saved then
        local warning = "Domain registered, but active domain could not be saved: " .. tostring(reason)
        printError("wifi: " .. warning)
        return true, warning
    end
    return true
end

function M.exportDomainToken(domain)
    domain = normalizeDomain(domain)
    if not domain then return nil, "Invalid domain name" end
    local token, reason = readToken(domain)
    if not token then return nil, reason or "No saved token for this domain" end
    return token
end

function M.importDomainToken(domain, token)
    domain = normalizeDomain(domain)
    if not domain then return false, "Invalid domain name" end
    if not validToken(token) then return false, "Invalid domain token" end
    return saveToken(domain, token)
end

function M.forgetDomainToken(domain)
    domain = normalizeDomain(domain)
    if not domain then return false, "Invalid domain name" end
    local path = tokenPath(domain)
    if not fs.exists(path) then return false, "No saved token for this domain" end
    if fs.isDir(path) then return false, "Saved token path is a directory" end
    fs.delete(path)
    return true
end

function M.releaseDomain(domain, routerName)
    domain = normalizeDomain(domain)
    if not domain then return false, "Invalid domain name" end
    local router = getRouter(routerName)
    if not router and not activeConnection then return false, "Not connected to a Wi-Fi router" end
    local token, reason = readToken(domain)
    if not token then return false, reason or "No saved token for this domain" end
    local wan = router and router.getWanIP() or activeConnection.wanIP
    local path = activePath(wan)
    local selected
    selected, reason = readFile(path)
    if reason then return false, reason end
    if selected == domain then fs.delete(path) end
    if router then
        local released = router.releaseDomain(domain, token)
        return released, not released and "Domain release was rejected" or nil
    end
    return sendAirRequest({ kind = "release_domain", domain = domain, token = token }, true)
end

function M.sendWAN(destinationWanOrDomain, destinationLan, message, routerName)
    local router = getRouter(routerName)
    if router then
        return router.sendWAN(destinationWanOrDomain, destinationLan, message)
    end
    if not activeConnection then
        return false, "Not connected to a Wi-Fi router"
    end
    if not validMessage(message) then
        return false, "Message must be a serializable string, number, boolean, or table"
    end
    return sendAirRequest({
        kind = "send_wan",
        destinationWan = destinationWanOrDomain,
        destinationLan = destinationLan,
        message = message,
    })
end

function M.configureWifi(ssid, password, routerName)
    local router = assert(getRouter(routerName), "No Wi-Fi Router peripheral found")
    return router.configureWifi(ssid, password)
end

function M.configureWan(secret, routerName)
    local router = assert(getRouter(routerName), "No Wi-Fi Router peripheral found")
    return router.configureWan(secret)
end

function M.listNearbyRouters(routerName, duration)
    if duration ~= nil and (type(duration) ~= "number" or duration <= 0 or duration > 60) then
        return nil, "Discovery duration must be between 0 and 60 seconds"
    end
    local router = getRouter(routerName)
    if router then
        return router.listNearbyRouters()
    end
    local opened, count = openRednet()
    if count == 0 then
        return nil, "A wireless modem is required for air discovery"
    end
    local nearby = {}
    local seen = {}
    local deadline = os.epoch("utc") + (duration or 3.5) * 1000
    while os.epoch("utc") < deadline do
        local senderID, packet = rednet.receive(CLIENT_PROTOCOL,
            math.max(0, (deadline - os.epoch("utc")) / 1000))
        if not senderID then
            break
        end
        if type(packet) == "table" and packet.kind == "beacon" and validWan(packet.wanIP)
                and type(packet.ssid) == "string" and type(packet.range) == "number"
                and packet.range >= 1 and packet.range <= 1024 then
            local _, routerX, routerY, routerZ =
                packet.wanIP:match("^(.+)%.(-?%d+)%.(-?%d+)%.(-?%d+)$")
            routerX, routerY, routerZ = tonumber(routerX), tonumber(routerY), tonumber(routerZ)
            if routerX and routerY and routerZ then
                if not seen[packet.wanIP] then
                    seen[packet.wanIP] = true
                    nearby[#nearby + 1] = {
                        ssid = packet.ssid,
                        wanIP = packet.wanIP,
                        security = packet.secured and "password" or "open",
                        x = routerX,
                        y = routerY,
                        z = routerZ,
                        computerID = senderID,
                        connectedClients = packet.connectedClients,
                    }
                end
            end
        end
    end
    closeRednet(opened)
    return nearby
end

function M.run(routerName)
    local router = assert(getRouter(routerName), "No Wi-Fi Router peripheral found")
    local openedModems = {}
    local bridgeEnabled = false

    local function runLoop()
        local modemCount = 0
        for _, name in ipairs(peripheral.getNames()) do
            if peripheral.getType(name) == "modem" then
                if not rednet.isOpen(name) then
                    rednet.open(name)
                    openedModems[#openedModems + 1] = name
                end
                modemCount = modemCount + 1
            end
        end

        assert(modemCount > 0, "A wired or wireless CC:Tweaked modem is required")
        assert(router.setBridge(true), "Another computer is already bridging this router")
        bridgeEnabled = true

        local linkInfo = router.getLinkInfo()
        local wanIP = linkInfo.wanIP
        local ssid = linkInfo.ssid
        local wifiRevision = linkInfo.revision
        local wanRevision = linkInfo.wanRevision
        local wanEnabled = linkInfo.wanEnabled
        local networkConfig = router.getNetworkConfig()
        local announceInterval = networkConfig.announceIntervalSeconds
        local peerTimeout = networkConfig.peerTimeoutSeconds
        local peers = {}
        local challenges = {}
        local challengeGeneration = 0
        local remoteChallenges = {}
        local pendingAir = {}
        local airClients = {}

        local function sendAirResponse(clientID, packet)
            local ok, protected = pcall(router.sealAir, clientID, textutils.serialize(packet))
            if not ok then
                printError("wifi: could not protect wireless response: " .. tostring(protected))
                return
            end
            rednet.send(clientID, { kind = "secure", ciphertext = protected }, CLIENT_PROTOCOL)
        end

        local function drainWanPackets()
            while true do
                local packet = router.nextWanPacket()
                if not packet then
                    return
                end
                local peer = peers[packet.destinationWan]
                if peer and os.epoch("utc") - peer.seen <= peerTimeout * 1000 then
                    rednet.send(peer.computerID, {
                        kind = "data",
                        destinationWan = packet.destinationWan,
                        ciphertext = packet.ciphertext,
                    }, PROTOCOL)
                else
                    printError("wifi: destination peer route unavailable: " .. packet.destinationWan)
                    peers[packet.destinationWan] = nil
                    router.reportWANFailure(packet.sourceComputerId, packet.destinationWan)
                end
            end
        end

        local function sendDomainAnnouncement(peerWan)
            local peer = peers[peerWan]
            local remote = remoteChallenges[peerWan]
            if peer and remote and remote.computerID == peer.computerID then
                local ok, ciphertext = pcall(router.sealDomainAnnouncement, peerWan, remote.challenge)
                if not ok then
                    printError("wifi: could not protect domain announcement: " .. tostring(ciphertext))
                    return
                end
                rednet.send(peer.computerID, {
                    kind = "domains",
                    wanIP = wanIP,
                    ciphertext = ciphertext,
                }, PROTOCOL)
            end
        end

        local function handleDomainAnnouncement(senderID, packet)
            if not validWan(packet.wanIP) or type(packet.ciphertext) ~= "string" then
                printError("wifi: rejected malformed domain announcement")
                return
            end
            local peer = peers[packet.wanIP]
            if not peer or peer.computerID ~= senderID then
                return
            end
            local ok, protected = pcall(router.openDomainAnnouncement, packet.wanIP, packet.ciphertext)
            if not ok or type(protected) ~= "table" or protected.challenge ~= peer.challenge
                    or type(protected.sequence) ~= "number" or protected.sequence <= peer.domainSequence then
                printError("wifi: rejected unauthenticated or replayed domain announcement")
                return
            end
            local domains = protected.records
            if type(domains) ~= "table" or #domains > 254 then
                printError("wifi: rejected malformed domain announcement")
                return
            end
            local count = 0
            for _, record in ipairs(domains) do
                count = count + 1
                if type(record) ~= "table" or type(record.domain) ~= "string"
                        or #record.domain > 253
                        or (record.lanIP ~= "" and not validLan(record.lanIP)) then
                    printError("wifi: rejected malformed domain record")
                    return
                end
            end
            if count ~= #domains then
                printError("wifi: rejected malformed domain list")
                return
            end
            router.clearPeerDomains(packet.wanIP)
            for _, record in ipairs(domains) do
                if not router.registerPeerDomain(packet.wanIP, record.domain, record.lanIP) then
                    printError("wifi: could not register peer domain " .. record.domain)
                end
            end
            peer.domainSequence = protected.sequence
            peer.seen = os.epoch("utc")
        end

        local function handleWanPacket(senderID, packet)
            if type(packet) ~= "table" then
                printError("wifi: rejected malformed WAN control packet")
                return
            end
            if not wanEnabled then
                return
            end
            if packet.kind == "hello" and validWan(packet.wanIP) and packet.wanIP ~= wanIP
                    and type(packet.challenge) == "string"
                    and #packet.challenge > 0 and #packet.challenge <= 128 then
                local count = 0
                for _ in pairs(remoteChallenges) do
                    count = count + 1
                end
                if remoteChallenges[packet.wanIP] or count < 256 then
                    remoteChallenges[packet.wanIP] = {
                        computerID = senderID,
                        challenge = packet.challenge,
                        seen = os.epoch("utc"),
                    }
                else
                    printError("wifi: too many pending WAN peers")
                    return
                end
                rednet.send(senderID, {
                    kind = "auth",
                    wanIP = wanIP,
                    ssid = ssid,
                    challenge = packet.challenge,
                    proof = router.makeLinkProof(packet.challenge, wanIP),
                }, PROTOCOL)
                sendDomainAnnouncement(packet.wanIP)
            elseif packet.kind == "auth" and validWan(packet.wanIP) then
                if packet.wanIP ~= wanIP and type(packet.challenge) == "string"
                        and challenges[packet.challenge]
                        and os.epoch("utc") <= challenges[packet.challenge].expires
                        and not challenges[packet.challenge].authenticated[packet.wanIP]
                        and (not peers[packet.wanIP]
                            or challenges[packet.challenge].generation > peers[packet.wanIP].challengeGeneration)
                        and type(packet.proof) == "string"
                        and router.verifyLinkProof(packet.challenge, packet.wanIP, packet.proof) then
                    challenges[packet.challenge].authenticated[packet.wanIP] = senderID
                    peers[packet.wanIP] = {
                        computerID = senderID,
                        seen = os.epoch("utc"),
                        challenge = packet.challenge,
                        challengeGeneration = challenges[packet.challenge].generation,
                        domainSequence = -1,
                    }
                    sendDomainAnnouncement(packet.wanIP)
                end
            elseif packet.kind == "domains" then
                handleDomainAnnouncement(senderID, packet)
            end
        end

        local function announce()
            if wanEnabled then
                local challenge = router.newLinkChallenge()
                challengeGeneration = challengeGeneration + 1
                challenges[challenge] = {
                    expires = os.epoch("utc") + peerTimeout * 1000,
                    authenticated = {},
                    generation = challengeGeneration,
                }
                rednet.broadcast({
                    kind = "hello",
                    wanIP = wanIP,
                    ssid = ssid,
                    challenge = challenge,
                }, PROTOCOL)
            end
            local _, x, y, z = wanIP:match("^(.+)%.(-?%d+)%.(-?%d+)%.(-?%d+)$")
            local wifiInfo = router.getWifi()
            rednet.broadcast({
                kind = "beacon",
                wanIP = wanIP,
                ssid = ssid,
                x = tonumber(x),
                y = tonumber(y),
                z = tonumber(z),
                range = wifiInfo.range,
                secured = wifiInfo.security == "password",
                connectedClients = wifiInfo.connectedClients,
            }, CLIENT_PROTOCOL)
            for peerWan in pairs(peers) do
                sendDomainAnnouncement(peerWan)
            end
        end

        local function refreshWanConfig(currentLinkInfo)
            if currentLinkInfo.wanRevision ~= wanRevision then
                for peerWan in pairs(peers) do
                    router.clearPeerDomains(peerWan)
                end
                wanRevision = currentLinkInfo.wanRevision
                wanEnabled = currentLinkInfo.wanEnabled
                peers = {}
                challenges = {}
                remoteChallenges = {}
            end
        end

        announce()
        local timer = os.startTimer(announceInterval)

        while true do
            local event, first, second, protocol = os.pullEvent()

            if event == "timer" and first == timer then
                local currentLinkInfo = router.getLinkInfo()
                refreshWanConfig(currentLinkInfo)
                networkConfig = router.getNetworkConfig()
                announceInterval = networkConfig.announceIntervalSeconds
                peerTimeout = networkConfig.peerTimeoutSeconds
                if currentLinkInfo.wanIP ~= wanIP or currentLinkInfo.ssid ~= ssid
                        or currentLinkInfo.revision ~= wifiRevision then
                    for peerWan in pairs(peers) do
                        router.clearPeerDomains(peerWan)
                    end
                    wanIP = currentLinkInfo.wanIP
                    ssid = currentLinkInfo.ssid
                    wifiRevision = currentLinkInfo.revision
                    peers = {}
                    challenges = {}
                    remoteChallenges = {}
                    pendingAir = {}
                    airClients = {}
                end
                local now = os.epoch("utc")
                for challenge, state in pairs(challenges) do
                    if now > state.expires then
                        challenges[challenge] = nil
                    end
                    for peerWan, remote in pairs(remoteChallenges) do
                        if now - remote.seen > peerTimeout * 1000 then
                            remoteChallenges[peerWan] = nil
                        end
                    end
                end
                for peerWan, peer in pairs(peers) do
                    if now - peer.seen > peerTimeout * 1000 then
                        router.clearPeerDomains(peerWan)
                        peers[peerWan] = nil
                        remoteChallenges[peerWan] = nil
                    end
                end
                drainWanPackets()
                announce()
                timer = os.startTimer(announceInterval)
            elseif event == "wifi_domains_changed" then
                for peerWan in pairs(peers) do
                    sendDomainAnnouncement(peerWan)
                end
            elseif event == "rednet_message" and protocol == PROTOCOL and type(second) == "table" then
                refreshWanConfig(router.getLinkInfo())
                local senderID = first
                local packet = second
                if packet.kind == "data" and packet.destinationWan == wanIP
                        and type(packet.ciphertext) == "string" then
                    local sourceWan
                    local now = os.epoch("utc")
                    for peerWan, peer in pairs(peers) do
                        if peer.computerID == senderID and now - peer.seen <= peerTimeout * 1000 then
                            sourceWan = peerWan
                            break
                        end
                    end
                    if sourceWan then
                        local ok, accepted = pcall(router.receiveWAN, sourceWan, wanIP, packet.ciphertext)
                        if not ok then
                            printError("wifi: rejected encrypted WAN packet: " .. tostring(accepted))
                        elseif not accepted then
                            printError("wifi: WAN packet rejected by destination router: " .. wanIP)
                        end
                    else
                        printError("wifi: rejected WAN packet: source is not an authenticated peer")
                    end
                else
                    handleWanPacket(senderID, packet)
                end
            elseif event == "rednet_message" and protocol == CLIENT_PROTOCOL and type(second) == "table" then
                local senderID = first
                local packet = second
                local verified = false
                if packet.kind == "secure_sensitive" and airClients[senderID]
                        and type(packet.ciphertext) == "string" then
                    local ok, result = pcall(router.processAirDomainRequest, senderID, packet.ciphertext)
                    if ok and type(result) == "table" then
                        result.kind = "send_result"
                        sendAirResponse(senderID, result)
                    else
                        printError("wifi: rejected confidential domain request: " .. tostring(result))
                    end
                    packet = {}
                elseif packet.kind == "secure" and airClients[senderID] and type(packet.ciphertext) == "string" then
                    local ok, plaintext = pcall(router.openAir, senderID, packet.ciphertext)
                    if ok then
                        local parsed, result = pcall(textutils.unserialize, plaintext)
                        if parsed and type(result) == "table" then
                            packet = result
                            verified = true
                        else
                            printError("wifi: rejected malformed wireless packet")
                            packet = {}
                        end
                    else
                        printError("wifi: rejected wireless packet: " .. tostring(plaintext))
                        packet = {}
                    end
                end
                if packet.kind == "join" and type(packet.ssid) == "string"
                        and type(packet.clientNonce) == "string" and #packet.clientNonce > 0
                        and #packet.clientNonce <= 128 then
                    local ok, challenge = pcall(router.beginAirJoin, senderID, packet.ssid,
                        packet.clientNonce)
                    if ok and type(challenge) == "table" and challenge.challenge then
                        pendingAir[senderID] = {
                            clientNonce = packet.clientNonce,
                            challenge = challenge.challenge,
                            ssid = challenge.ssid,
                            expires = os.epoch("utc") + 15000,
                        }
                        rednet.send(senderID, {
                            kind = "challenge",
                            clientNonce = packet.clientNonce,
                            challenge = challenge.challenge,
                            wanIP = challenge.wanIP,
                            ssid = challenge.ssid,
                        }, CLIENT_PROTOCOL)
                    end
                elseif packet.kind == "proof" and type(packet.challenge) == "string"
                        and type(packet.clientNonce) == "string" and type(packet.proof) == "string" then
                    local pending = pendingAir[senderID]
                    if pending and pending.expires >= os.epoch("utc")
                            and pending.clientNonce == packet.clientNonce
                            and pending.challenge == packet.challenge then
                        pendingAir[senderID] = nil
                        local ok, result = pcall(router.completeAirJoin, senderID, packet.challenge, packet.proof)
                        if ok and type(result) == "table" and result.success then
                            airClients[senderID] = true
                            result.kind = "join_result"
                            result.clientNonce = packet.clientNonce
                            result.routerComputerID = os.getComputerID()
                            sendAirResponse(senderID, result)
                        else
                            rednet.send(senderID, {
                                kind = "join_result",
                                clientNonce = packet.clientNonce,
                                ssid = pending.ssid,
                                success = false,
                                reason = "Invalid credentials, out of range, or router at capacity",
                            }, CLIENT_PROTOCOL)
                        end
                    end
                elseif verified and packet.kind == "disconnect" and airClients[senderID] then
                    airClients[senderID] = nil
                    router.disconnectAir(senderID)
                elseif verified and packet.kind == "send" and airClients[senderID]
                        and type(packet.recipient) == "string" then
                    local ok, sent = pcall(router.sendAir, senderID, packet.recipient, packet.message)
                    if not ok then
                        printError("wifi: send failed: " .. tostring(sent))
                    elseif not sent then
                        printError("wifi: message could not be delivered to " .. packet.recipient)
                    end
                    drainWanPackets()
                    if type(packet.requestId) == "string" and #packet.requestId <= 128 then
                        sendAirResponse(senderID, {
                            kind = "send_result",
                            requestId = packet.requestId,
                            success = ok and sent == true,
                            reason = not ok and tostring(sent) or (not sent and "Router could not route message" or nil),
                        })
                    end
                elseif verified and packet.kind == "send_wan" and airClients[senderID]
                        and type(packet.destinationWan) == "string"
                        and (packet.destinationLan == nil or type(packet.destinationLan) == "string") then
                    local ok, sent = pcall(router.sendAirWAN, senderID, packet.destinationWan,
                        packet.destinationLan, packet.message)
                    drainWanPackets()
                    if type(packet.requestId) == "string" and #packet.requestId <= 128 then
                        sendAirResponse(senderID, {
                            kind = "send_result",
                            requestId = packet.requestId,
                            success = ok and sent == true,
                            reason = not ok and tostring(sent) or (not sent and "Router could not route message" or nil),
                        })
                    end
                elseif verified and packet.kind == "resolve" and airClients[senderID]
                        and type(packet.domain) == "string" and #packet.domain <= 253
                        and type(packet.requestId) == "string" and #packet.requestId <= 128 then
                    sendAirResponse(senderID, {
                        kind = "resolve_result",
                        requestId = packet.requestId,
                        lanIP = router.resolve(packet.domain),
                    })
                elseif verified and packet.kind == "get_domains" and airClients[senderID]
                        and type(packet.requestId) == "string" and #packet.requestId <= 128 then
                    sendAirResponse(senderID, {
                        kind = "domains_result",
                        requestId = packet.requestId,
                        domains = router.getKnownDomainRecords(),
                    })
                elseif verified and packet.kind == "receive" and airClients[senderID] then
                    local data = router.receiveAir(senderID)
                    sendAirResponse(senderID, {
                        kind = "receive_result",
                        available = type(data) == "table",
                        data = data,
                    })
                end
            elseif event == "wifi_wan_send" then
                drainWanPackets()
            end
        end
    end

    local ok, err = pcall(runLoop)
    if bridgeEnabled then
        router.setBridge(false)
    end
    for _, name in ipairs(openedModems) do
        rednet.close(name)
    end
    if not ok then
        error(err, 0)
    end
end

return M
