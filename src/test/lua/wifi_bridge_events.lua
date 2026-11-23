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

local wan = "22.145.56.55"
local registrations, responses, beacons = {}, {}, {}
local router = {
    setBridge = function() return true end,
    getLinkInfo = function()
        return { wanIP = wan, ssid = "test", revision = 0 }
    end,
    getNetworkConfig = function() return { announceIntervalSeconds = 3, peerTimeoutSeconds = 12 } end,
    getWifi = function()
        return { range = 60, security = "password", connectedClients = 0, x = 1, y = 2, z = 3 }
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
    { "timer", 1 },
    { "rednet_message", 7, { kind = "domains", wanIP = "22.145.56.56" }, "wifi_router_v1" },
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
    find = function() return nil end,
    getNames = function() return { "left" } end,
    getType = function() return "modem" end,
}
local discoveryPackets = {}
rednet = {
    isOpen = function() return false end,
    open = function() end,
    close = function() end,
    broadcast = function(packet)
        if packet.kind == "beacon" then beacons[#beacons + 1] = packet end
    end,
    receive = function()
        local packet = table.remove(discoveryPackets, 1)
        if packet then return packet.senderID, packet.message end
    end,
    send = function(id, packet, protocol)
        responses[#responses + 1] = { id = id, packet = packet, protocol = protocol }
    end,
}
printError = function() end

local wifi = dofile("src\\main\\resources\\data\\computercraft\\lua\\rom\\modules\\main\\wifi.lua")
discoveryPackets = {
    {
        senderID = 7,
        message = {
            kind = "beacon", wanIP = "22.145.56.55", ssid = "nearby", range = 60,
            x = 4, y = 5, z = 6,
        },
    },
    {
        senderID = 8,
        message = {
            kind = "beacon", wanIP = "22.345.556.55", ssid = "invalid", range = 60,
            x = 7, y = 8, z = 9,
        },
    },
}
local nearby = wifi.listNearbyRouters(nil, 0.1)
assert(#nearby == 1 and nearby[1].wanIP == "22.145.56.55")
assert(nearby[1].x == 4 and nearby[1].y == 5 and nearby[1].z == 6)
assert(wifi.configureWan == nil, "WAN secret must not be configurable")
local ok, err = pcall(wifi.run, router)
assert(not ok and tostring(err):find("test complete", 1, true))
assert(#beacons == 1 and beacons[1].wanIP == wan)
assert(beacons[1].x == 1 and beacons[1].y == 2 and beacons[1].z == 3)
assert(#registrations == 1 and registrations[1] == "client.example")
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