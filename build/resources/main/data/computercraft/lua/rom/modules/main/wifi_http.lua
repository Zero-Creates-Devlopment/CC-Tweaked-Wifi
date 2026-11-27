-- HTTP-style request/response helpers built on wifi.send/wifi.receive threads.
local wifi = require("wifi")

local M = {}

local METHODS = { "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT" }
local VALID = {}
for _, method in ipairs(METHODS) do
    VALID[method] = true
end

local DEFAULT_TIMEOUT = 10

local function normalizeMethod(method)
    if type(method) ~= "string" then
        return nil
    end
    method = method:upper()
    return VALID[method] and method or nil
end

local function normalizePath(path)
    if type(path) ~= "string" or path == "" then
        return "/"
    end
    if path:sub(1, 1) ~= "/" then
        path = "/" .. path
    end
    return path
end

local function splitQuery(path)
    local base, query = path:match("^([^?]*)%??(.*)$")
    local params = {}
    for pair in query:gmatch("[^&]+") do
        local key, value = pair:match("^([^=]*)=?(.*)$")
        params[key] = value
    end
    return base, params, query
end

local function lowerHeaders(headers)
    local result = {}
    if type(headers) == "table" then
        for key, value in pairs(headers) do
            if type(key) == "string" then
                result[key:lower()] = tostring(value)
            end
        end
    end
    return result
end

-- Sends one request to a WAN address or domain and waits for the reply on the same thread.
-- options: headers (table), body (string/table), timeout (seconds), router (peripheral name)
function M.request(method, host, path, options)
    options = options or {}
    method = normalizeMethod(method)
    if not method then
        return nil, "Unsupported HTTP method"
    end
    if type(host) ~= "string" then
        return nil, "Host must be a WAN address or domain"
    end
    if type(options) ~= "table" then
        return nil, "Options must be a table"
    end
    local timeout = options.timeout or DEFAULT_TIMEOUT
    local sent, thread = wifi.send(host, {
        type = "http_request",
        method = method,
        path = normalizePath(path),
        headers = lowerHeaders(options.headers),
        body = options.body,
    })
    if not sent then
        return nil, thread
    end
    while true do
        local packet, reason = wifi.receive(thread, timeout, options.router)
        if not packet then
            return nil, reason or "Request timed out"
        end
        local response = packet.message
        if type(response) == "table" and response.type == "http_response" then
            return {
                status = response.status,
                ok = type(response.status) == "number" and response.status >= 200 and response.status < 300,
                headers = lowerHeaders(response.headers),
                body = response.body,
                from = packet.fromWan,
                thread = thread,
            }
        end
    end
end

for _, method in ipairs(METHODS) do
    local name = method:lower()
    M[name] = function(host, path, options)
        return M.request(method, host, path, options)
    end
end

-- Body-carrying helpers take the body before options: M.post(host, path, body, options)
for _, method in ipairs({ "POST", "PUT", "PATCH" }) do
    M[method:lower()] = function(host, path, body, options)
        options = options or {}
        options.body = body
        return M.request(method, host, path, options)
    end
end

local Server = {}
Server.__index = Server

-- Creates a router for incoming requests. Call :route(method, path, handler) then :run().
function M.server()
    return setmetatable({ routes = {}, fallback = nil }, Server)
end

-- method may be "*" for any method; path may end in "*" as a prefix wildcard.
function Server:route(method, path, handler)
    if type(handler) ~= "function" then
        error("handler must be a function", 2)
    end
    if method ~= "*" then
        method = normalizeMethod(method)
        if not method then
            error("unsupported HTTP method", 2)
        end
    end
    self.routes[#self.routes + 1] = { method = method, path = normalizePath(path), handler = handler }
    return self
end

for _, method in ipairs(METHODS) do
    Server[method:lower()] = function(self, path, handler)
        return self:route(method, path, handler)
    end
end

function Server:default(handler)
    self.fallback = handler
    return self
end

local function matches(route, method, path)
    if route.method ~= "*" and route.method ~= method then
        return false
    end
    if route.path:sub(-1) == "*" then
        return path:sub(1, #route.path - 1) == route.path:sub(1, -2)
    end
    return route.path == path
end

-- Handlers receive (request) and return status, body, headers or a table {status, body, headers}.
function Server:handle(request)
    local path, query, rawQuery = splitQuery(request.path)
    request.path, request.query, request.rawQuery = path, query, rawQuery
    local pathExists = false
    for _, route in ipairs(self.routes) do
        if matches(route, request.method, path) then
            return route.handler(request)
        end
        if route.path == path or (route.path:sub(-1) == "*" and path:sub(1, #route.path - 1) == route.path:sub(1, -2)) then
            pathExists = true
        end
    end
    if self.fallback then
        return self.fallback(request)
    end
    if request.method == "OPTIONS" then
        return 204, "", { allow = "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS" }
    end
    if pathExists then
        return 405, "Method Not Allowed"
    end
    return 404, "Not Found"
end

local function buildResponse(request, status, body, headers)
    if type(status) == "table" then
        status, body, headers = status.status, status.body, status.headers
    end
    if type(status) ~= "number" then
        status, body = 200, status
    end
    if request.method == "HEAD" then
        body = nil
    end
    return {
        type = "http_response",
        status = status,
        headers = lowerHeaders(headers),
        body = body,
    }
end

-- Handles a single request packet and sends the response on the request thread.
function Server:respond(packet)
    local message = packet.message
    if type(message) ~= "table" or message.type ~= "http_request" then
        return false
    end
    local method = normalizeMethod(message.method)
    local response
    if not method or type(message.path) ~= "string" then
        response = buildResponse({ method = "GET" }, 400, "Bad Request")
    else
        local request = {
            method = method,
            path = message.path,
            headers = lowerHeaders(message.headers),
            body = message.body,
            fromWan = packet.fromWan,
            fromLan = packet.fromLan,
            thread = packet.thread,
        }
        local ok, status, body, headers = pcall(self.handle, self, request)
        if ok then
            response = buildResponse(request, status, body, headers)
        else
            response = buildResponse(request, 500, "Internal Server Error")
        end
    end
    local target = packet.fromWan
    if type(target) ~= "string" or target == "" or not packet.thread then
        return false
    end
    return wifi.send(target, response, packet.thread)
end

-- Blocks serving requests until options.stop() returns true (if provided) or an error occurs.
function Server:run(options)
    options = options or {}
    while true do
        if options.stop and options.stop() then
            return true
        end
        local packet = wifi.receive(options.poll or 1, options.router)
        if packet then
            self:respond(packet)
        end
    end
end

return M
