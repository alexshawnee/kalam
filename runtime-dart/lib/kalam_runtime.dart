import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

const int _version = 1;
const int _frameTypeUnary = 0;
const int _frameTypeStreamChunk = 1;
const int _frameTypeStreamEnd = 2;
const int _frameTypeError = 3;

// Header: version(1) + requestId(4) + frameType(1) + methodLen(4) + payloadLen(4) = 14
const int _headerSize = 14;

// ── Frame ──────────────────────────────────────────────────────────────

class Frame {
  final int version;
  final int requestId;
  final int frameType;
  final String method;
  final Uint8List payload;

  Frame({
    required this.version,
    required this.requestId,
    required this.frameType,
    required this.method,
    required this.payload,
  });
}

Uint8List encodeFrame({
  required int requestId,
  required int frameType,
  required String method,
  required Uint8List payload,
}) {
  final methodBytes = Uint8List.fromList(method.codeUnits);
  final total = _headerSize + methodBytes.length + payload.length;
  final buffer = ByteData(total);
  var offset = 0;

  buffer.setUint8(offset, _version);
  offset += 1;
  buffer.setUint32(offset, requestId);
  offset += 4;
  buffer.setUint8(offset, frameType);
  offset += 1;
  buffer.setUint32(offset, methodBytes.length);
  offset += 4;

  final bytes = buffer.buffer.asUint8List();
  bytes.setRange(offset, offset + methodBytes.length, methodBytes);
  offset += methodBytes.length;

  buffer.setUint32(offset, payload.length);
  offset += 4;
  bytes.setRange(offset, offset + payload.length, payload);

  return bytes;
}

// ── FrameReader ────────────────────────────────────────────────────────

class FrameReader {
  final BytesBuilder _buffer = BytesBuilder(copy: false);
  int _consumed = 0;

  void add(List<int> chunk) {
    _buffer.add(chunk);
  }

  Frame? tryReadFrame() {
    final bytes = _buffer.toBytes();
    final available = bytes.length - _consumed;

    if (available < _headerSize) return null;

    final view = ByteData.sublistView(bytes, _consumed);

    final methodLen = view.getUint32(6);
    final neededForPayloadLen = _headerSize + methodLen;

    if (available < neededForPayloadLen) return null;

    final payloadLenOffset = 10 + methodLen;
    final payloadLen = view.getUint32(payloadLenOffset);
    final totalLen = neededForPayloadLen + payloadLen;

    if (available < totalLen) return null;

    final frameStart = _consumed;
    final frame = Frame(
      version: view.getUint8(0),
      requestId: view.getUint32(1),
      frameType: view.getUint8(5),
      method: String.fromCharCodes(bytes, frameStart + 10, frameStart + 10 + methodLen),
      payload: Uint8List.fromList(
        bytes.sublist(frameStart + neededForPayloadLen, frameStart + totalLen),
      ),
    );

    _consumed += totalLen;
    return frame;
  }

  void compact() {
    if (_consumed == 0) return;
    final bytes = _buffer.toBytes();
    final remaining = bytes.sublist(_consumed);
    _buffer.clear();
    _consumed = 0;
    if (remaining.isNotEmpty) {
      _buffer.add(remaining);
    }
  }
}

// ── Exception ──────────────────────────────────────────────────────────

class KalamException implements Exception {
  final String method;
  final String message;

  KalamException(this.method, this.message);

  @override
  String toString() => 'KalamException($method): $message';
}

void _checkError(Frame frame) {
  if (frame.frameType == _frameTypeError) {
    throw KalamException(
      frame.method,
      String.fromCharCodes(frame.payload),
    );
  }
}

// ── Client ─────────────────────────────────────────────────────────────

class Kalam {
  Kalam._();

  static final Kalam instance = Kalam._();

  String? _socketPath;
  Socket? _socket;
  FrameReader? _reader;
  int _nextRequestId = 1;
  final Map<int, Completer<Frame>> _pendingCalls = {};
  final Map<int, StreamController<Frame>> _pendingStreams = {};

  void useSockets(String path) {
    _socketPath = path;
    _disconnect();
  }

  Future<void> _ensureConnected() async {
    if (_socket != null) return;

    final address = InternetAddress(_socketPath!, type: InternetAddressType.unix);
    _socket = await Socket.connect(address, 0);
    _reader = FrameReader();

    _socket!.listen(
      _onData,
      onError: (_) => _disconnect(),
      onDone: _disconnect,
    );
  }

  void _onData(List<int> data) {
    _reader!.add(data);

    while (true) {
      final frame = _reader!.tryReadFrame();
      if (frame == null) break;
      _reader!.compact();

      final id = frame.requestId;

      // Route to pending unary call
      final completer = _pendingCalls.remove(id);
      if (completer != null) {
        completer.complete(frame);
        continue;
      }

      // Route to pending stream
      final controller = _pendingStreams[id];
      if (controller != null) {
        if (frame.frameType == _frameTypeStreamEnd) {
          _pendingStreams.remove(id);
          controller.close();
        } else if (frame.frameType == _frameTypeError) {
          _pendingStreams.remove(id);
          controller.addError(KalamException(
            frame.method,
            String.fromCharCodes(frame.payload),
          ));
          controller.close();
        } else {
          controller.add(frame);
        }
        continue;
      }
    }
  }

  void _disconnect() {
    _socket?.destroy();
    _socket = null;
    _reader = null;
    for (final c in _pendingCalls.values) {
      c.completeError(StateError('Connection lost'));
    }
    _pendingCalls.clear();
    for (final s in _pendingStreams.values) {
      s.addError(StateError('Connection lost'));
      s.close();
    }
    _pendingStreams.clear();
  }

  Future<Uint8List> call(String method, Uint8List payload) async {
    if (_socketPath == null) {
      throw StateError('Kalam not initialized. Call useSockets() first.');
    }

    await _ensureConnected();

    final requestId = _nextRequestId++;
    final completer = Completer<Frame>();
    _pendingCalls[requestId] = completer;

    _socket!.add(encodeFrame(
      requestId: requestId,
      frameType: _frameTypeUnary,
      method: method,
      payload: payload,
    ));

    final response = await completer.future;
    _checkError(response);
    return response.payload;
  }

  Stream<Uint8List> stream(String method, Uint8List payload) {
    if (_socketPath == null) {
      throw StateError('Kalam not initialized. Call useSockets() first.');
    }

    final requestId = _nextRequestId++;
    final controller = StreamController<Frame>();
    _pendingStreams[requestId] = controller;

    () async {
      await _ensureConnected();

      _socket!.add(encodeFrame(
        requestId: requestId,
        frameType: _frameTypeUnary,
        method: method,
        payload: payload,
      ));
    }();

    return controller.stream.map((frame) {
      _checkError(frame);
      return frame.payload;
    });
  }

  Future<void> close() async {
    _socket?.destroy();
    _socket = null;
    _reader = null;
    _pendingCalls.clear();
    _pendingStreams.clear();
  }
}

// ── Server ─────────────────────────────────────────────────────────────

abstract class ServiceRouter {
  Future<void> handle(String method, Uint8List payload, ResponseSink sink);
}

class ResponseSink {
  final Socket _client;
  final int _requestId;
  final String _method;

  ResponseSink(this._client, this._requestId, this._method);

  void sendUnary(Uint8List payload) {
    _client.add(encodeFrame(
      requestId: _requestId,
      frameType: _frameTypeUnary,
      method: _method,
      payload: payload,
    ));
  }

  void sendChunk(Uint8List payload) {
    _client.add(encodeFrame(
      requestId: _requestId,
      frameType: _frameTypeStreamChunk,
      method: _method,
      payload: payload,
    ));
  }

  void sendEnd() {
    _client.add(encodeFrame(
      requestId: _requestId,
      frameType: _frameTypeStreamEnd,
      method: _method,
      payload: Uint8List(0),
    ));
  }

  void sendError(String message) {
    _client.add(encodeFrame(
      requestId: _requestId,
      frameType: _frameTypeError,
      method: _method,
      payload: Uint8List.fromList(message.codeUnits),
    ));
  }
}

class KalamServer {
  KalamServer._();

  static final KalamServer instance = KalamServer._();

  ServerSocket? _server;

  Future<void> serve(String path, ServiceRouter router) async {
    final file = File(path);
    if (file.existsSync()) file.deleteSync();

    final address = InternetAddress(path, type: InternetAddressType.unix);
    _server = await ServerSocket.bind(address, 0);

    print('Kalam server listening on $path');

    await for (final client in _server!) {
      _handleClient(client, router);
    }
  }

  void _handleClient(Socket client, ServiceRouter router) {
    final reader = FrameReader();

    client.listen(
      (data) async {
        reader.add(data);

        while (true) {
          final request = reader.tryReadFrame();
          if (request == null) break;
          reader.compact();

          final sink = ResponseSink(client, request.requestId, request.method);
          try {
            await router.handle(request.method, request.payload, sink);
          } catch (e) {
            sink.sendError(e.toString());
          }
          await client.flush();
        }
      },
      onError: (e) => print('Client error: $e'),
      onDone: () => client.close(),
    );
  }

  Future<void> close() async {
    await _server?.close();
    _server = null;
  }
}
