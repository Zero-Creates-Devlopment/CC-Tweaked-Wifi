local values, nextValue = {}, 0
textutils = {
    serialize = function(value)
        nextValue = nextValue + 1
        local key = tostring(nextValue)
        values[key] = value
        return key
    end,
    unserialize = function(value) return values[value] end,
}

local wan = "minecraft:overworld.1.2.3"
local peerWan = "minecraft:overworld.4.5.6"
local records = {
    { domain = "storage.example", lanIP = "192.168.1.100" },
    { domain = "offline.example", lanIP = "" },
}
local oldRecords = { { domain = "storage.example", lanIP = "192.168.1.199" } }
local registrations, peerRecords, responses = {}, {}, {}
local peerUpdates = 0
local challenges = 0
local router = {
    setBridge = function() return true end,
    getLinkInfo = function()
        return { wanIP = wan, ssid = "test", revision = 0, wanRevision = 0, wanEnabled = true }
    end,
    getNetworkConfig = function() return { announceIntervalSeconds = 3, peerTimeoutSeconds = 12 } end,
    getWifi = function() return { range = 60, security = "password", connectedClients = 0 } end,
    newLinkChallenge = function()
        challenges = challenges + 1
        return "our-challenge-" .. challenges
    end,
    makeLinkProof = function() return "proof" end,
    verifyLinkProof = function(_, _, proof) return proof == "valid" end,
    getDomainRecords = function() return {} end,
    nextWanPacket = function() return nil end,
    sealDomainAnnouncement = function() return "outbound" end,
    openDomainAnnouncement = function(_, ciphertext)
        if ciphertext == "valid" then
            return { challenge = "our-challenge-2", sequence = 2, records = records }
        elseif ciphertext == "old" then
            return { challenge = "our-challenge-1", sequence = 1, records = oldRecords }
        end
        error("authentication failed")
    end,
    clearPeerDomains = function() peerRecords = {} end,
    registerPeerDomain = function(_, domain, ip)
        peerUpdates = peerUpdates + 1
        peerRecords[domain] = ip
        return true
    end,
    beginAirJoin = function()
        return { challenge = "join-challenge", ssid = "test", wanIP = wan }
    end,
    completeAirJoin = function()
        return { success = true, ip = "192.168.1.101", wanIP = wan, ssid = "test" }
    end,
    sealAir = function(_, serialized) return "sealed:" .. serialized end,
    openAir = function(_, ciphertext)
        if ciphertext == "valid-client" then
            return textutils.serialize({ kind = "register_domain", domain = "client.example", requestId = "r1" })
        end
        error("authentication failed")
    end,
    processAirDomainRequest = function(_, ciphertext)
        if ciphertext ~= "valid-sensitive" then error("authentication failed") end
        registrations[#registrations + 1] = "client.example"
        return { requestId = "r1", success = true }
    end,
}

local events = {
    { "rednet_message", 7, { kind = "auth", wanIP = peerWan, challenge = "our-challenge-1", proof = "bad" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "domains", wanIP = peerWan, ciphertext = "valid" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "hello", wanIP = peerWan, challenge = "peer-challenge" }, "wifi_router_v1" },
    { "timer", 1 },
    { "rednet_message", 7, { kind = "auth", wanIP = peerWan, challenge = "our-challenge-2", proof = "valid" }, "wifi_router_v1" },
    { "rednet_message", 8, { kind = "domains", wanIP = peerWan, ciphertext = "valid" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "domains", wanIP = peerWan, ciphertext = "valid" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "auth", wanIP = peerWan, challenge = "our-challenge-1", proof = "valid" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "domains", wanIP = peerWan, ciphertext = "old" }, "wifi_router_v1" },
    { "rednet_message", 7, { kind = "domains", wanIP = peerWan, ciphertext = "forged" }, "wifi_router_v1" },
    { "rednet_message", 11, { kind = "join", ssid = "test", clientNonce = "nonce" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "proof", challenge = "join-challenge", clientNonce = "nonce", proof = "proof" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "register_domain", domain = "hijacked.example" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "secure", ciphertext = "forged" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "secure", ciphertext = "valid-client" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "secure_sensitive", ciphertext = "forged" }, "wifi_client_v1" },
    { "rednet_message", 11, { kind = "secure_sensitive", ciphertext = "valid-sensitive" }, "wifi_client_v1" },
}
local index = 0
os.epoch = function() return 1000 end
os.getComputerID = function() return 9 end
os.startTimer = function() return 1 end
os.pullEvent = function()
    index = index + 1
    if not events[index] then error("test complete") end
    return table.unpack(events[index])
end
peripheral = {
    getNames = function() return { "left" } end,
    getType = function() return "modem" end,
}
rednet = {
    isOpen = function() return false end,
    open = function() end,
    close = function() end,
    broadcast = function() end,
    send = function(id, packet, protocol)
        responses[#responses + 1] = { id = id, packet = packet, protocol = protocol }
    end,
}
printError = function() end

local wifi = dofile("src\\main\\resources\\data\\computercraft\\lua\\rom\\modules\\main\\wifi.lua")
local ok, err = pcall(wifi.run, router)
assert(not ok and tostring(err):find("test complete", 1, true))
assert(#registrations == 1 and registrations[1] == "client.example")
assert(peerRecords["storage.example"] == "192.168.1.100")
assert(peerRecords["offline.example"] == "", "offline reservations must be retained")
assert(peerUpdates == 2, "forged or replayed peer records must not be applied")
local acknowledged = false
for _, response in ipairs(responses) do
    if response.id == 11 and response.packet.kind == "secure" then
        local payload = textutils.unserialize(response.packet.ciphertext:sub(8))
        if payload and payload.kind == "send_result" and payload.requestId == "r1" and payload.success then
            acknowledged = true
        end
    end
end
assert(acknowledged, "domain registration must receive an authenticated acknowledgement")
print("Bridge security tests passed")