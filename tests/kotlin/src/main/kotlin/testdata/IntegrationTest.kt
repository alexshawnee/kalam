package testdata

import com.kalam.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MyUserService : UserServiceHandler {
    override suspend fun getUser(request: GetUserRequest): GetUserResponse {
        println("  server ← GetUser(id: ${request.id})")
        return GetUserResponse(name = "User ${request.id}", email = "user${request.id}@test.com")
    }

    override fun listUsers(request: ListUsersRequest): Flow<ListUsersResponse> = flow {
        println("  server ← ListUsers(query: ${request.query})")
        for (i in 1..3) {
            emit(ListUsersResponse(name = "User $i", email = "user$i@test.com"))
        }
    }
}

fun main() = runBlocking {
    val sock = "/tmp/kalam_kt_test.sock"

    val router = UserServiceRouter(MyUserService())
    KalamServer.instance.serve(sock, router)
    delay(100)

    Kalam.instance.useSockets(sock)

    // Test unary
    println("→ GetUser(id: 42)")
    val r1 = UserService.getUser(GetUserRequest(id = 42))
    println("← name: ${r1.name}, email: ${r1.email}")
    check(r1.name == "User 42")

    // Test streaming
    println("→ ListUsers(query: \"all\")")
    val users = UserService.listUsers(ListUsersRequest(query = "all"))
        .toList()
    for (user in users) {
        println("← name: ${user.name}, email: ${user.email}")
    }
    check(users.size == 3)
    check(users[0].name == "User 1")
    check(users[2].name == "User 3")

    // Test multiplexing — 3 concurrent unary calls
    println("→ Concurrent: GetUser(1), GetUser(2), GetUser(3)")
    val results = (1..3).map { id ->
        async {
            UserService.getUser(GetUserRequest(id = id))
        }
    }.awaitAll()
    for (r in results) {
        println("← name: ${r.name}")
    }
    check(results[0].name == "User 1")
    check(results[1].name == "User 2")
    check(results[2].name == "User 3")

    // Test error handling
    println("→ Calling unknown method...")
    try {
        Kalam.instance.call("UserService/NonExistent", ByteArray(0))
        error("Should have thrown")
    } catch (e: KalamException) {
        println("← Error caught: $e")
        check(e.message.contains("Unknown method"))
    }

    println("SUCCESS")

    Kalam.instance.close()
    KalamServer.instance.close()
}
