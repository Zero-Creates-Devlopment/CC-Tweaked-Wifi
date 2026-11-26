local root = "src\\main\\resources\\data\\computercraft\\lua\\rom\\"
package.path = root .. "modules\\main\\?.lua;" .. package.path

_G.wifi = nil
dofile(root .. "startup\\wifi.lua")
assert(type(wifi) == "table")
assert(type(wifi.send) == "function")
assert(type(wifi.receive) == "function")
assert(wifi == require("wifi"))

_G.wifi_http = nil
dofile(root .. "startup\\wifi_http.lua")
assert(type(wifi_http) == "table")
assert(type(wifi_http.request) == "function")
assert(type(wifi_http.server) == "function")
assert(wifi_http == require("wifi_http"))

print("Wi-Fi startup global tests passed")
