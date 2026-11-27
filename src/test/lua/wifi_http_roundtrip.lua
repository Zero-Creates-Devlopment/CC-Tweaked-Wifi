local queue = {}
local counter = 0
package.loaded.wifi = {
    send = function(to, message, thread)
        counter = counter + 1
        thread = thread or string.rep(tostring(counter), 64):sub(1, 64)
        queue[#queue + 1] = { fromWan = "1.1.1.1", fromLan = "192.168.1.2", message = message, thread = thread, to = to }
        return true, thread
    end,
    receive = function(first)
        local thread = type(first) == "string" and first or nil
        for i, packet in ipairs(queue) do
            if not thread or packet.thread == thread then
                return table.remove(queue, i)
            end
        end
    end,
}
local http = dofile("src\\main\\resources\\data\\computercraft\\lua\\rom\\modules\\main\\wifi_http.lua")

local server = http.server()
server:get("/hello", function() return 200, "hi", { ["X-A"] = "b" } end)
server:post("/echo", function(request) return { status = 201, body = request.body } end)
server:route("*", "/any/*", function(request) return 200, request.method .. request.query.x end)
server:get("/boom", function() error("x") end)

local function call(method, path, options)
    local original = package.loaded.wifi.receive
    package.loaded.wifi.receive = function(thread)
        for i, packet in ipairs(queue) do
            if packet.message.type == "http_request" then
                server:respond(table.remove(queue, i))
                break
            end
        end
        return original(thread)
    end
    local response, reason = http.request(method, "example.com", path, options)
    package.loaded.wifi.receive = original
    return response, reason
end

local r = call("get", "/hello")
assert(r and r.status == 200 and r.ok and r.body == "hi" and r.headers["x-a"] == "b")
r = call("POST", "/echo", { body = "data" })
assert(r.status == 201 and r.body == "data")
assert(call("PUT", "/any/x?x=7").body == "PUT7")
assert(call("DELETE", "/hello").status == 405)
assert(call("GET", "/nope").status == 404)
assert(call("GET", "/boom").status == 500)
assert(call("OPTIONS", "/nope").status == 204)
r = call("HEAD", "/hello")
assert(r.status == 405 or r.body == nil)
assert(not http.request("BREW", "example.com", "/"))
print("wifi_http tests passed")
