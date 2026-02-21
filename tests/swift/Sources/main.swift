import Foundation
import KalamRuntime

class MyUserService: UserServiceHandler {
    func getUser(_ request: Testdata_GetUserRequest, completion: @escaping (Result<Testdata_GetUserResponse, Error>) -> Void) {
        print("  server <- GetUser(id: \(request.id))")
        var response = Testdata_GetUserResponse()
        response.name = "User \(request.id)"
        response.email = "user\(request.id)@test.com"
        completion(.success(response))
    }

    func listUsers(_ request: Testdata_ListUsersRequest, onChunk: @escaping (Testdata_ListUsersResponse) -> Void, onEnd: @escaping () -> Void) {
        print("  server <- ListUsers(query: \(request.query))")
        for i in 1...3 {
            var response = Testdata_ListUsersResponse()
            response.name = "User \(i)"
            response.email = "user\(i)@test.com"
            onChunk(response)
        }
        onEnd()
    }
}

let sock = "/tmp/kalam_swift_test.sock"

let router = UserServiceRouter(MyUserService())
KalamServer.shared.serve(sock, router)
try await Task.sleep(nanoseconds: 100_000_000)

Kalam.shared.useSockets(sock)

// Test unary
print("-> GetUser(id: 42)")
var req = Testdata_GetUserRequest()
req.id = 42
let r1 = try await UserService.getUser(req)
print("<- name: \(r1.name), email: \(r1.email)")
assert(r1.name == "User 42")

// Test streaming
print("-> ListUsers(query: \"all\")")
var listReq = Testdata_ListUsersRequest()
listReq.query = "all"
var users: [Testdata_ListUsersResponse] = []
let stream = UserService.listUsers(listReq)
for try await user in stream {
    print("<- name: \(user.name), email: \(user.email)")
    users.append(user)
}
assert(users.count == 3)
assert(users[0].name == "User 1")
assert(users[2].name == "User 3")

// Test multiplexing - 3 concurrent unary calls
print("-> Concurrent: GetUser(1), GetUser(2), GetUser(3)")
let results = try await withThrowingTaskGroup(of: (Int, Testdata_GetUserResponse).self) { group in
    for id: Int32 in 1...3 {
        group.addTask {
            var r = Testdata_GetUserRequest()
            r.id = id
            return (Int(id), try await UserService.getUser(r))
        }
    }
    var map: [Int: Testdata_GetUserResponse] = [:]
    for try await (id, resp) in group {
        map[id] = resp
    }
    return map
}
for id in 1...3 {
    print("<- name: \(results[id]!.name)")
}
assert(results[1]!.name == "User 1")
assert(results[2]!.name == "User 2")
assert(results[3]!.name == "User 3")

// Test error handling
print("-> Calling unknown method...")
let errorSem = DispatchSemaphore(value: 0)
var caughtError: KalamException?
Kalam.shared.call("UserService/NonExistent", Data()) { result in
    if case .failure(let error) = result {
        caughtError = error as? KalamException
    }
    errorSem.signal()
}
errorSem.wait()
print("<- Error caught: \(caughtError!)")
assert(caughtError!.message.contains("Unknown method"))

print("SUCCESS")

Kalam.shared.disconnect()
KalamServer.shared.close()
exit(0)
