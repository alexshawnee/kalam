import 'dart:io';
import 'dart:typed_data';
import 'package:kalam_runtime/kalam_runtime.dart';
import 'package:kalam_integration_test/generated/user.pb.dart';
import 'package:kalam_integration_test/generated/user.klm.dart';

class MyUserService extends UserServiceHandler {
  @override
  Future<GetUserResponse> getUser(GetUserRequest request) async {
    print('  server ← GetUser(id: ${request.id})');
    return GetUserResponse(
      name: 'User ${request.id}',
      email: 'user${request.id}@test.com',
    );
  }

  @override
  Stream<ListUsersResponse> listUsers(ListUsersRequest request) async* {
    print('  server ← ListUsers(query: ${request.query})');
    for (var i = 1; i <= 3; i++) {
      yield ListUsersResponse(name: 'User $i', email: 'user$i@test.com');
    }
  }
}

void main() async {
  const sock = '/tmp/kalam_test.sock';

  final router = UserServiceRouter(MyUserService());
  KalamServer.instance.serve(sock, router);
  await Future.delayed(Duration(milliseconds: 100));

  Kalam.instance.useSockets(sock);

  // Test unary
  print('→ GetUser(id: 42)');
  final r1 = await UserService.getUser(GetUserRequest(id: 42));
  print('← name: ${r1.name}, email: ${r1.email}');
  assert(r1.name == 'User 42');

  // Test streaming
  print('→ ListUsers(query: "all")');
  final users = <ListUsersResponse>[];
  await for (final user in UserService.listUsers(ListUsersRequest(query: 'all'))) {
    print('← name: ${user.name}, email: ${user.email}');
    users.add(user);
  }
  assert(users.length == 3);
  assert(users[0].name == 'User 1');
  assert(users[2].name == 'User 3');

  // Test multiplexing — 3 concurrent unary calls
  print('→ Concurrent: GetUser(1), GetUser(2), GetUser(3)');
  final futures = [
    UserService.getUser(GetUserRequest(id: 1)),
    UserService.getUser(GetUserRequest(id: 2)),
    UserService.getUser(GetUserRequest(id: 3)),
  ];
  final results = await Future.wait(futures);
  for (final r in results) {
    print('← name: ${r.name}');
  }
  assert(results[0].name == 'User 1');
  assert(results[1].name == 'User 2');
  assert(results[2].name == 'User 3');

  // Test error handling
  print('→ Calling unknown method...');
  try {
    await Kalam.instance.call('UserService/NonExistent', Uint8List(0));
    assert(false, 'Should have thrown');
  } on KalamException catch (e) {
    print('← Error caught: $e');
    assert(e.message.contains('Unknown method'));
  }

  print('SUCCESS');

  await Kalam.instance.close();
  await KalamServer.instance.close();
  exit(0);
}
