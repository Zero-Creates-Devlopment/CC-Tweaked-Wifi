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
local registered, delivered
local inbox = {}
local registrationCount, sensitivePackets = 0, {}
local token = string.rep("a", 64)
local router = {
    setBridge = function() return true end,
    getLinkInfo = function()
        return { wanIP = wan, ssid = "test", revision = 0, wanRevision = 0, wanEnabled = false }
    end,
    getNetworkConfig = function() return { announceIntervalSeconds = 3, peerTimeoutSeconds = 12 } end,
    getWifi = function()
        return { range = 60, security = "password", connectedClients = 0, x = 1, y = 2, z = 3 }
    end,
    beginAirJoin = function()
        return { challenge = "challenge", ssid = "test", wanIP = wan }
    end,
    completeAirJoin = function(_, _, proof)
        assert(type(proof) == "string" and #proof == 64)
        return { success = true, ip = "192.168.1.101", wanIP = wan, ssid = "test" }
    end,
    sealAir = function(_, value) return "router:" .. value end,
    openAir = function(_, value)
        assert(value:sub(1, 7) == "client:")
        return value:sub(8)
    end,
    processAirDomainRequest = function(_, ciphertext)
        local plaintext = assert(sensitivePackets[ciphertext], "invalid sensitive packet")
        local kind, domain, supplied, requestId = plaintext:match("^([^\0]+)\0([^\0]+)\0([^\0]+)\0([^\0]+)$")
        assert(supplied == token)
        if kind == "register_domain" then
            registrationCount = registrationCount + 1
            registered = domain
        elseif kind == "release_domain" then
            registered = nil
        else
            error("unexpected domain request")
        end
        return { requestId = requestId, success = true }
    end,
    sendAir = function(_, recipient, message, thread)
        delivered = { recipient, message, thread }
        return true
    end,
    nextWanPacket = function() return nil end,
    receiveAir = function(_, thread)
        for i, packet in ipairs(inbox) do
            if not thread or packet.thread == thread then
                return table.remove(inbox, i)
            end
        end
    end,
    disconnectAir = function() return true end,
}

local who = 11
local bridgeQueue, clientQueue = {}, {}
local files = {}
fs = {
    exists = function(path) return files[path] ~= nil end,
    isDir = function() return false end,
    makeDir = function() end,
    delete = function(path) files[path] = nil end,
    open = function(path, mode)
        if mode == "r" then
            if not files[path] then return nil, "File not found" end
            return { readAll = function() return files[path] end, close = function() end }
        end
        local value = ""
        return {
            write = function(data) value = value .. data end,
            close = function() files[path] = value end,
        }
    end,
}
math.random = function() return 42 end
os.epoch = function() return 1000 end
os.getComputerID = function() return who end
os.startTimer = function() return 1 end
os.queueEvent = function() end
os.pullEvent = function(filter)
    if who == 9 then
        local event, a, b, c = coroutine.yield()
        if event == "terminate" then error("test complete") end
        return event, a, b, c
    end
    return filter
end
peripheral = {
    find = function() return nil end,
    getNames = function() return { "left" } end,
    getType = function() return "modem" end,
}
wifi_air_crypto = {
    newToken = function() return token end,
    seal = function(address, value)
        assert(address == wan)
        return "client:" .. value
    end,
    open = function(address, value)
        assert(address == wan and value:sub(1, 7) == "router:")
        return value:sub(8)
    end,
    sealSensitive = function(address, value)
        assert(address == wan)
        local key = "encrypted:" .. tostring(#sensitivePackets + 1)
        sensitivePackets[key] = value
        return key
    end,
}
rednet = {
    isOpen = function() return false end,
    open = function() end,
    close = function() end,
    broadcast = function(packet, protocol)
        if who == 11 then
            bridgeQueue[#bridgeQueue + 1] = { "rednet_message", 11, packet, protocol }
        end
    end,
    send = function(id, packet, protocol)
        if who == 11 then
            assert(id == 9)
            if packet.kind == "secure_sensitive" then
                assert(not packet.ciphertext:find(token, 1, true), "token must not be sent in plaintext")
            end
            bridgeQueue[#bridgeQueue + 1] = { "rednet_message", 11, packet, protocol }
        else
            assert(id == 11)
            clientQueue[#clientQueue + 1] = { 9, packet }
        end
    end,
}
printError = function(message) error(message) end

local wifi = dofile("src\\main\\resources\\data\\computercraft\\lua\\rom\\modules\\main\\wifi.lua")
local bridge = coroutine.create(function() wifi.run(router) end)
local function pump()
    while #bridgeQueue > 0 do
        local event = table.remove(bridgeQueue, 1)
        who = 9
        local ok, err = coroutine.resume(bridge, table.unpack(event))
        assert(ok, tostring(err))
        who = 11
    end
end
rednet.receive = function()
    pump()
    local response = table.remove(clientQueue, 1)
    if response then return table.unpack(response) end
end
who = 9
local ok, err = coroutine.resume(bridge)
assert(ok, tostring(err))
who = 11

assert(wifi.connect("test", "password123"))
assert(wifi.registerDomain("client.example"))
assert(registered == "client.example")
assert(wifi.exportDomainToken("client.example") == token)
assert(not wifi.importDomainToken("client.example", string.rep("b", 64)))
assert(wifi.forgetDomainToken("client.example"))
assert(wifi.importDomainToken("client.example", token))
local sent, thread = wifi.send("22.145.56.56", "hello")
assert(sent and thread == token)
assert(delivered[1] == "22.145.56.56" and delivered[2] == "hello" and delivered[3] == thread)
assert(wifi.send("22.145.56.56", "reply", thread))
assert(delivered[3] == thread)
assert(not wifi.send("22.145.56.56", "bad", "invalid"))
inbox = {
    { from = "192.168.1.100", message = "other", thread = string.rep("b", 64) },
    { from = "192.168.1.100", message = "reply", thread = thread },
}
local message = wifi.receive(thread, 2)
assert(message and message.message == "reply" and message.thread == thread)
assert(wifi.receive(2).message == "other", "filtered receive must preserve other threads")
assert(wifi.disconnect())
pump()
assert(wifi.connect("test", "password123"))
assert(registrationCount == 2 and registered == "client.example", "reconnect must restore the saved domain")
assert(wifi.releaseDomain("client.example"))
assert(registered == nil)
assert(wifi.disconnect())
pump()
assert(wifi.connect("test", "password123"))
assert(registrationCount == 2, "released domains must not be re-registered on reconnect")
assert(wifi.disconnect())
pump()
router.getWanIP = function() return wan end
router.connect = function() return true end
router.disconnect = function() return true end
router.send = function(recipient, message, thread)
    delivered = { recipient, message, thread }
    return true
end
router.receive = function(thread)
    for i, packet in ipairs(inbox) do
        if not thread or packet.thread == thread then
            return table.remove(inbox, i)
        end
    end
end
router.registerDomain = function(domain, supplied)
    assert(supplied == token)
    registrationCount = registrationCount + 1
    registered = domain
    return true
end
router.releaseDomain = function(domain, supplied)
    assert(domain == "client.example" and supplied == token)
    registered = nil
    return true
end
peripheral.find = function() return router end
assert(wifi.connect("test", "password123"))
local wiredSent, wiredThread = wifi.send("22.145.56.56", "wired")
assert(wiredSent and wiredThread == token and delivered[3] == wiredThread)
inbox = {
    { message = "other", thread = string.rep("b", 64) },
    { message = "wired reply", thread = wiredThread },
}
assert(wifi.receive(wiredThread, 0).message == "wired reply")
assert(wifi.receive().message == "other")
assert(wifi.registerDomain("client.example"))
assert(wifi.disconnect())
assert(wifi.connect("test", "password123"))
assert(registrationCount == 4 and registered == "client.example", "wired reconnect must restore the domain")
assert(wifi.releaseDomain("client.example"))
assert(wifi.disconnect())
who = 9
local ended, endReason = coroutine.resume(bridge, "terminate")
assert(not ended and tostring(endReason):find("test complete", 1, true))
who = 11
print("Wireless client round-trip tests passed")
